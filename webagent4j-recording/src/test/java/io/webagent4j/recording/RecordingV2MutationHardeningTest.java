package io.webagent4j.recording;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.webagent4j.workflow.IWorkflowCondition;
import io.webagent4j.workflow.IWorkflowVariables;
import io.webagent4j.workflow.Workflow;
import io.webagent4j.workflow.WorkflowConditions;
import io.webagent4j.workflow.WorkflowEngine;
import io.webagent4j.workflow.WorkflowExecution;
import io.webagent4j.workflow.WorkflowExecutionPlan;
import io.webagent4j.workflow.WorkflowInputs;
import io.webagent4j.workflow.WorkflowPlanNode;
import io.webagent4j.workflow.WorkflowPlanner;
import io.webagent4j.workflow.WorkflowStepId;
import io.webagent4j.workflow.WorkflowSteps;
import io.webagent4j.workflow.WorkflowVariable;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/**
 * REC2-HARD coverage: rather than hand-building fixture JSON from scratch, this suite captures ONE
 * real {@code WorkflowExecution} (produced by the actual {@link WorkflowEngine} from a workflow
 * combining a leaf {@code ACTION}, a branching {@code ifElse}, a bounded {@code loop}, and a
 * bounded {@code parallel} step) into a genuinely valid {@link WorkflowRecordingV2}, then mutates
 * its canonical JSON encoding one invariant at a time and asserts {@link
 * JsonWorkflowRecordingV2Codec#decode} rejects every mutation - see {@code
 * docs/recording.md#recording-v2} and this module's own "treat a recording as hostile input"
 * principle. A mutation that happens to still validate would mean the underlying invariant it
 * targets is not actually enforced; this suite exists to catch exactly that regression, on the true
 * engine-produced shape rather than only on independently hand-built fixtures (see {@link
 * RecordingV2ModelInvariantsTest}, {@link RecordingV2PlanTreeValidatorTest}, and {@link
 * WorkflowParallelRecordingV2Test} for those).
 */
class RecordingV2MutationHardeningTest {

    private final WorkflowEngine engine = new WorkflowEngine();
    private final WorkflowRecorderV2 recorder = new WorkflowRecorderV2();
    private final JsonWorkflowRecordingV2Codec codec = new JsonWorkflowRecordingV2Codec();
    private final ObjectMapper mapper = new ObjectMapper();

    private static final class CountingUntilFalseCondition implements IWorkflowCondition {
        private final int trueCount;
        private final AtomicInteger evaluations = new AtomicInteger();

        CountingUntilFalseCondition(int trueCount) {
            this.trueCount = trueCount;
        }

        @Override
        public boolean evaluate(IWorkflowVariables variables) {
            return evaluations.getAndIncrement() < trueCount;
        }

        @Override
        public String describe() {
            return "untilFalse(" + trueCount + ")";
        }

        @Override
        public Set<WorkflowVariable<?>> referencedVariables() {
            return Set.of();
        }
    }

    /**
     * Builds and executes, for real, a workflow combining every control-flow shape this suite's
     * mutations target: {@code s1} (leaf {@code ACTION}), {@code dec} ({@code ifElse}, selecting
     * {@code THEN} since {@code flag=true}), {@code lp} (a two-iteration bounded loop), and {@code
     * par} (a two-branch bounded parallel step) - then captures it into a genuinely valid {@link
     * WorkflowRecordingV2} via the real {@link WorkflowRecorderV2}, never a hand-built fixture.
     */
    private WorkflowRecordingV2 realValidRecording() {
        WorkflowVariable<Boolean> flag = WorkflowVariable.publicValue("flag", Boolean.class);
        WorkflowVariable<String> out1 = WorkflowVariable.publicValue("out1", String.class);
        WorkflowVariable<String> branchOut =
                WorkflowVariable.publicValue("branchOut", String.class);
        WorkflowVariable<Boolean> bodyOut = WorkflowVariable.publicValue("bodyOut", Boolean.class);
        WorkflowVariable<Boolean> p0out = WorkflowVariable.publicValue("p0out", Boolean.class);
        WorkflowVariable<Boolean> p1out = WorkflowVariable.publicValue("p1out", Boolean.class);
        Workflow workflow =
                Workflow.builder("wf-v2-mutation")
                        .requiredInput(flag)
                        .step(
                                WorkflowSteps.action(
                                        "s1",
                                        vars ->
                                                new FakePreparedAction<>(
                                                        ActionResults.success("v")),
                                        out1))
                        .step(
                                WorkflowSteps.ifElse(
                                        "dec",
                                        WorkflowConditions.isTrue(flag),
                                        List.of(
                                                WorkflowSteps.assign(
                                                        "then1", branchOut, "then-val")),
                                        List.of(
                                                WorkflowSteps.assign(
                                                        "else1", branchOut, "else-val"))))
                        .step(
                                WorkflowSteps.loop(
                                        "lp",
                                        new CountingUntilFalseCondition(2),
                                        5,
                                        List.of(WorkflowSteps.assign("body1", bodyOut, true))))
                        .step(
                                WorkflowSteps.parallel(
                                        "par",
                                        List.of(
                                                List.of(WorkflowSteps.assign("pb0", p0out, true)),
                                                List.of(WorkflowSteps.assign("pb1", p1out, true)))))
                        .build();
        WorkflowExecutionPlan plan = WorkflowPlanner.plan(workflow);
        WorkflowExecution execution =
                engine.executeWithTree(workflow, WorkflowInputs.builder().put(flag, true).build());
        assertThat(execution.result().completed()).isTrue();
        return recorder.record(
                new RecordingId("rec-v2-mutation"),
                Instant.parse("2026-01-01T00:00:00Z"),
                plan,
                execution);
    }

    /** Encodes {@code recording}, applies {@code mutation} to its parsed JSON tree, re-encodes. */
    private String mutatedJson(WorkflowRecordingV2 recording, Consumer<ObjectNode> mutation) {
        try {
            ObjectNode root = (ObjectNode) mapper.readTree(codec.encode(recording));
            mutation.accept(root);
            return mapper.writeValueAsString(root);
        } catch (Exception e) {
            throw new AssertionError("failed to build mutated fixture JSON", e);
        }
    }

    private ArrayNode topNodes(ObjectNode root) {
        return (ArrayNode) root.get("nodes");
    }

    private void assertMutationRejected(WorkflowRecordingV2 base, Consumer<ObjectNode> mutation) {
        String mutated = mutatedJson(base, mutation);
        assertThatThrownBy(() -> codec.decode(mutated))
                .isInstanceOf(RecordingFormatException.class);
    }

    // --- REC2-HARD-001: wrong tree node type -----------------------------------------------

    @Test
    void rec2Hard001WrongTreeNodeTypeIsRejected() {
        WorkflowRecordingV2 base = realValidRecording();
        assertMutationRejected(
                base,
                root -> ((ObjectNode) topNodes(root).get(0).get("step")).put("stepType", "ASSIGN"));
    }

    // --- REC2-HARD-002: missing required child ----------------------------------------------

    @Test
    void rec2Hard002MissingRequiredChildIsRejected() {
        WorkflowRecordingV2 base = realValidRecording();
        assertMutationRejected(
                base,
                root -> {
                    ArrayNode decChildren = (ArrayNode) topNodes(root).get(1).get("children");
                    decChildren.remove(0);
                });
    }

    // --- REC2-HARD-003: extra child ----------------------------------------------------------

    @Test
    void rec2Hard003ExtraChildIsRejected() {
        WorkflowRecordingV2 base = realValidRecording();
        assertMutationRejected(
                base,
                root -> {
                    ArrayNode decChildren = (ArrayNode) topNodes(root).get(1).get("children");
                    decChildren.add(decChildren.get(0).deepCopy());
                });
    }

    // --- REC2-HARD-004: duplicate node (duplicate step ID) ----------------------------------

    @Test
    void rec2Hard004DuplicateLoopIterationNodeIsRejected() {
        WorkflowRecordingV2 base = realValidRecording();
        assertMutationRejected(
                base,
                root -> {
                    ArrayNode loopChildren = (ArrayNode) topNodes(root).get(2).get("children");
                    // Duplicating iteration 0 produces two LOOP_ITERATION nodes both claiming
                    // step ID "lp#0" - a duplicate step ID across the flattened tree.
                    loopChildren.insert(1, loopChildren.get(0).deepCopy());
                });
    }

    // --- REC2-HARD-005: duplicate/mismatched output -----------------------------------------

    @Test
    void rec2Hard005OutputRenamedToAnotherStepsOutputIsRejected() {
        WorkflowRecordingV2 base = realValidRecording();
        assertMutationRejected(
                base,
                root -> {
                    ObjectNode s1Step = (ObjectNode) topNodes(root).get(0).get("step");
                    ObjectNode output = (ObjectNode) s1Step.get("output");
                    // s1 genuinely declares "out1" - claiming "p0out" (a different step's own
                    // declared output name) instead no longer matches s1's own plan declaration.
                    output.put("name", "p0out");
                });
    }

    // --- REC2-HARD-006: unknown step ID -------------------------------------------------------

    @Test
    void rec2Hard006UnknownStepIdIsRejected() {
        WorkflowRecordingV2 base = realValidRecording();
        assertMutationRejected(
                base,
                root ->
                        ((ObjectNode) topNodes(root).get(0).get("step"))
                                .put("stepId", "ghost-step-not-in-plan"));
    }

    // --- REC2-HARD-007: plan/tree positional mismatch (reordering) ---------------------------

    @Test
    void rec2Hard007ReorderedTopLevelNodesAreRejected() {
        WorkflowRecordingV2 base = realValidRecording();
        assertMutationRejected(
                base,
                root -> {
                    ArrayNode nodes = topNodes(root);
                    JsonNode first = nodes.get(0);
                    JsonNode second = nodes.get(1);
                    nodes.set(0, second);
                    nodes.set(1, first);
                });
    }

    // --- REC2-HARD-008: workflowId mismatch ---------------------------------------------------

    @Test
    void rec2Hard008WorkflowIdMismatchIsRejected() {
        WorkflowRecordingV2 base = realValidRecording();
        assertMutationRejected(base, root -> root.put("workflowId", "some-other-workflow"));
    }

    // --- REC2-HARD-009: impossible status/failure combination --------------------------------

    @Test
    void rec2Hard009FailedStatusWithoutFailureIsRejected() {
        WorkflowRecordingV2 base = realValidRecording();
        assertMutationRejected(
                base,
                root -> ((ObjectNode) topNodes(root).get(0).get("step")).put("status", "FAILED"));
    }

    // --- REC2-HARD-010: impossible branch selection -------------------------------------------

    @Test
    void rec2Hard010BranchSelectionInconsistentWithConditionOutcomeIsRejected() {
        WorkflowRecordingV2 base = realValidRecording();
        assertMutationRejected(
                base,
                root -> {
                    // dec's own condition outcome is recorded true (THEN was genuinely selected);
                    // claiming ELSE was selected instead contradicts that captured outcome even
                    // though ELSE is a structurally real branch of this ifElse.
                    ((ObjectNode) topNodes(root).get(1)).put("branchSelection", "ELSE");
                });
    }

    // --- deterministic first failure -----------------------------------------------------------

    /**
     * A plan/tree pair carrying two independent, positionally-ordered violations must always report
     * the very first one it encounters, across repeated direct-construction attempts of the
     * identical inputs - {@link RecordingV2PlanTreeValidator#validate} walks its input in a fixed,
     * index-ascending order, never a hash-order-dependent one, so which violation is reported never
     * varies from one attempt to the next. Constructed directly against {@link
     * WorkflowRecordingV2}'s own compact constructor (bypassing the JSON codec, which deliberately
     * collapses every invariant violation to the same generic message - see {@link
     * JsonWorkflowRecordingV2Codec#decode(String)} - so a JSON-level check alone could not
     * distinguish "the same failure every time" from "always the same generic wrapper text").
     */
    @Test
    void directConstructionReportsTheSameFirstFailureDeterministicallyAcrossRepeats() {
        WorkflowStepId idA = new WorkflowStepId("a");
        WorkflowStepId idB = new WorkflowStepId("b");
        io.webagent4j.workflow.WorkflowPlanOutput outputA =
                new io.webagent4j.workflow.WorkflowPlanOutput("outA", "Boolean", false);
        io.webagent4j.workflow.WorkflowPlanOutput outputB =
                new io.webagent4j.workflow.WorkflowPlanOutput("outB", "Boolean", false);
        List<WorkflowPlanNode> planNodes =
                List.of(
                        // Node A's plan declares ACTION - deliberately different from what is
                        // recorded below, so this is where validation must always stop first.
                        new WorkflowPlanNode(
                                idA,
                                io.webagent4j.workflow.WorkflowStepType.ACTION,
                                false,
                                java.util.Optional.empty(),
                                List.of()),
                        new WorkflowPlanNode(
                                idB,
                                io.webagent4j.workflow.WorkflowStepType.ASSIGN,
                                false,
                                java.util.Optional.of(outputB),
                                List.of()));
        WorkflowExecutionPlan plan =
                new WorkflowExecutionPlan(
                        new io.webagent4j.workflow.WorkflowId("wf-order"), planNodes);
        // Violation A (index 0): recorded stepType (ASSIGN) no longer matches the plan (ACTION).
        RecordedWorkflowStepV2 wrongTypeStep =
                new RecordedWorkflowStepV2(
                        idA,
                        io.webagent4j.workflow.WorkflowStepType.ASSIGN,
                        io.webagent4j.workflow.WorkflowStepStatus.SUCCEEDED,
                        java.util.Optional.empty(),
                        java.util.Optional.of(outputA),
                        java.util.Optional.empty(),
                        java.util.Optional.empty());
        // Violation B (index 1, never reached): recorded stepId no longer matches the plan's own
        // step ID - proves the second violation is never what gets reported, not merely that the
        // same generic message repeats.
        RecordedWorkflowStepV2 wrongIdStep =
                new RecordedWorkflowStepV2(
                        new WorkflowStepId("not-b"),
                        io.webagent4j.workflow.WorkflowStepType.ASSIGN,
                        io.webagent4j.workflow.WorkflowStepStatus.SUCCEEDED,
                        java.util.Optional.empty(),
                        java.util.Optional.of(outputB),
                        java.util.Optional.empty(),
                        java.util.Optional.empty());
        List<RecordedExecutionNodeV2> hostileNodes =
                List.of(
                        new RecordedExecutionNodeV2(
                                wrongTypeStep, java.util.Optional.empty(), List.of()),
                        new RecordedExecutionNodeV2(
                                wrongIdStep, java.util.Optional.empty(), List.of()));

        String firstMessage = constructionFailureMessage(plan, hostileNodes);
        assertThat(firstMessage).contains("step type");
        for (int i = 0; i < 5; i++) {
            assertThat(constructionFailureMessage(plan, hostileNodes)).isEqualTo(firstMessage);
        }
    }

    private String constructionFailureMessage(
            WorkflowExecutionPlan plan, List<RecordedExecutionNodeV2> nodes) {
        try {
            new WorkflowRecordingV2(
                    RecordingSchemaVersionV2.V2,
                    new RecordingId("rec-order"),
                    Instant.parse("2026-01-01T00:00:00Z"),
                    plan.workflowId(),
                    io.webagent4j.workflow.WorkflowStatus.COMPLETED,
                    plan,
                    nodes,
                    java.util.Optional.empty());
            throw new AssertionError("expected construction to reject the hostile fixture");
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        }
    }

    // --- linear-time validation on a large, genuinely valid recording ---------------------------

    /**
     * {@link RecordingV2PlanTreeValidator#validate} and {@link RecordingV2Invariants#validate} are
     * documented as linear in the plan/tree's own size - never a repeated global scan per node.
     * Builds a large (but within {@link JsonWorkflowRecordingV2Codec#MAX_NODES}), entirely valid,
     * directly-constructed sequential recording and asserts construction, encoding, and decoding
     * all complete quickly. This backs up, but is not the sole proof of, that documented complexity
     * - the actual guarantee is the single-pass positional walk in {@link
     * RecordingV2PlanTreeValidator} itself.
     */
    @Test
    void largeValidSequentialRecordingValidatesEncodesAndDecodesQuickly() {
        int count = 1_500;
        List<WorkflowPlanNode> planNodes = new java.util.ArrayList<>(count);
        List<RecordedExecutionNodeV2> execNodes = new java.util.ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            WorkflowStepId id = new WorkflowStepId("leaf-" + i);
            io.webagent4j.workflow.WorkflowPlanOutput declared =
                    new io.webagent4j.workflow.WorkflowPlanOutput("v" + i, "Boolean", false);
            planNodes.add(
                    new WorkflowPlanNode(
                            id,
                            io.webagent4j.workflow.WorkflowStepType.ASSIGN,
                            false,
                            java.util.Optional.of(declared),
                            List.of()));
            RecordedWorkflowStepV2 step =
                    new RecordedWorkflowStepV2(
                            id,
                            io.webagent4j.workflow.WorkflowStepType.ASSIGN,
                            io.webagent4j.workflow.WorkflowStepStatus.SUCCEEDED,
                            java.util.Optional.empty(),
                            java.util.Optional.of(declared),
                            java.util.Optional.empty(),
                            java.util.Optional.empty());
            execNodes.add(new RecordedExecutionNodeV2(step, java.util.Optional.empty(), List.of()));
        }
        WorkflowExecutionPlan plan =
                new WorkflowExecutionPlan(
                        new io.webagent4j.workflow.WorkflowId("wf-large"), planNodes);

        org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(
                java.time.Duration.ofSeconds(10),
                () -> {
                    WorkflowRecordingV2 recording =
                            new WorkflowRecordingV2(
                                    RecordingSchemaVersionV2.V2,
                                    new RecordingId("rec-large"),
                                    Instant.parse("2026-01-01T00:00:00Z"),
                                    new io.webagent4j.workflow.WorkflowId("wf-large"),
                                    io.webagent4j.workflow.WorkflowStatus.COMPLETED,
                                    plan,
                                    execNodes,
                                    java.util.Optional.empty());
                    String encoded = codec.encode(recording);
                    WorkflowRecordingV2 decoded = codec.decode(encoded);
                    assertThat(decoded).isEqualTo(recording);
                });
    }
}
