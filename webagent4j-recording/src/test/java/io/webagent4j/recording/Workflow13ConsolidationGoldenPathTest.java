package io.webagent4j.recording;

import static org.assertj.core.api.Assertions.assertThat;

import io.webagent4j.recording.replay.ReplayValidationFailure;
import io.webagent4j.recording.replay.ReplayValidator;
import io.webagent4j.workflow.IWorkflowCondition;
import io.webagent4j.workflow.IWorkflowVariables;
import io.webagent4j.workflow.Workflow;
import io.webagent4j.workflow.WorkflowConditions;
import io.webagent4j.workflow.WorkflowEngine;
import io.webagent4j.workflow.WorkflowExecution;
import io.webagent4j.workflow.WorkflowExecutionNode;
import io.webagent4j.workflow.WorkflowExecutionPlan;
import io.webagent4j.workflow.WorkflowInputs;
import io.webagent4j.workflow.WorkflowIntrospectionOutput;
import io.webagent4j.workflow.WorkflowIntrospectionReport;
import io.webagent4j.workflow.WorkflowIntrospector;
import io.webagent4j.workflow.WorkflowPlanBranch;
import io.webagent4j.workflow.WorkflowPlanNode;
import io.webagent4j.workflow.WorkflowPlanner;
import io.webagent4j.workflow.WorkflowStepResult;
import io.webagent4j.workflow.WorkflowStepType;
import io.webagent4j.workflow.WorkflowSteps;
import io.webagent4j.workflow.WorkflowVariable;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * 1.3 final-consolidation golden path: pushes ONE workflow combining every 1.3 control-flow
 * primitive (a secret leaf output, an {@code ifElse} whose {@code THEN} nests a bounded {@code
 * loop} whose body nests a bounded {@code parallel} step, and whose {@code ELSE} is a plain
 * unconditional step) through the entire chain this mission audits - {@link Workflow.Builder}'s own
 * {@code validate()}/{@code build()}, {@link WorkflowPlanner}, {@link WorkflowIntrospector}, {@link
 * WorkflowEngine}, {@link WorkflowExecutionNode}'s flattened tree, {@link WorkflowRecorderV2},
 * {@link JsonWorkflowRecordingV2Codec}, and {@link ReplayValidator} - asserting each stage agrees
 * with the others rather than merely asserting each stage's own behavior in isolation, which the
 * per-feature test suites already do exhaustively.
 *
 * <p>Where a cross-check has an independently-derivable expected value (declared output/node/type
 * counts), this test derives it by walking {@link WorkflowExecutionPlan} itself with a small local
 * helper rather than repeating a second hardcoded copy of the same magic number - so a genuine
 * Planner/Introspector count divergence would fail this test even if both individually happened to
 * report a plausible-looking number.
 */
class Workflow13ConsolidationGoldenPathTest {

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

    private static int countPlanNodes(List<WorkflowPlanNode> nodes) {
        int count = 0;
        for (WorkflowPlanNode node : nodes) {
            count++;
            for (WorkflowPlanBranch branch : node.branches()) {
                count += countPlanNodes(branch.nodes());
            }
        }
        return count;
    }

    private static int countPlanNodesOfType(List<WorkflowPlanNode> nodes, WorkflowStepType type) {
        int count = 0;
        for (WorkflowPlanNode node : nodes) {
            if (node.stepType() == type) {
                count++;
            }
            for (WorkflowPlanBranch branch : node.branches()) {
                count += countPlanNodesOfType(branch.nodes(), type);
            }
        }
        return count;
    }

    private static int countDeclaredOutputs(List<WorkflowPlanNode> nodes) {
        int count = 0;
        for (WorkflowPlanNode node : nodes) {
            if (node.declaredOutput().isPresent()) {
                count++;
            }
            for (WorkflowPlanBranch branch : node.branches()) {
                count += countDeclaredOutputs(branch.nodes());
            }
        }
        return count;
    }

    private static void flattenInto(
            List<WorkflowExecutionNode> nodes, List<WorkflowStepResult> out) {
        for (WorkflowExecutionNode node : nodes) {
            out.add(node.result());
            flattenInto(node.children(), out);
        }
    }

    @Test
    void mixedConditionalLoopParallelWorkflowIsConsistentAcrossTheEntire13Chain() {
        WorkflowVariable<Boolean> outerFlag =
                WorkflowVariable.publicValue("outerFlag", Boolean.class);
        WorkflowVariable<String> secretOut = WorkflowVariable.secret("secretOut");
        WorkflowVariable<Boolean> p0out = WorkflowVariable.publicValue("p0out", Boolean.class);
        WorkflowVariable<Boolean> p1out = WorkflowVariable.publicValue("p1out", Boolean.class);
        WorkflowVariable<String> simpleOut =
                WorkflowVariable.publicValue("simpleOut", String.class);
        String secretSentinel = "WA4J_GOLDEN_PATH_SECRET_SENTINEL_99182";

        Workflow.Builder builder =
                Workflow.builder("wf-golden-1.3")
                        .requiredInput(outerFlag)
                        .step(
                                WorkflowSteps.action(
                                        "s0",
                                        vars ->
                                                new FakePreparedAction<>(
                                                        ActionResults.success(secretSentinel)),
                                        secretOut))
                        .step(
                                WorkflowSteps.ifElse(
                                        "outer",
                                        WorkflowConditions.isTrue(outerFlag),
                                        List.of(
                                                WorkflowSteps.loop(
                                                        "lp",
                                                        new CountingUntilFalseCondition(2),
                                                        5,
                                                        List.of(
                                                                WorkflowSteps.parallel(
                                                                        "par",
                                                                        List.of(
                                                                                List.of(
                                                                                        WorkflowSteps
                                                                                                .assign(
                                                                                                        "pb0",
                                                                                                        p0out,
                                                                                                        true)),
                                                                                List.of(
                                                                                        WorkflowSteps
                                                                                                .assign(
                                                                                                        "pb1",
                                                                                                        p1out,
                                                                                                        true))))))),
                                        List.of(
                                                WorkflowSteps.assign(
                                                        "simple", simpleOut, "simple-val"))));

        // --- Builder vs Validation (section 6) ---
        assertThat(builder.validate().valid()).isTrue();
        Workflow workflow = builder.build();

        // --- Builder vs Planner (section 7) ---
        WorkflowExecutionPlan plan = WorkflowPlanner.plan(workflow);
        assertThat(plan.workflowId()).isEqualTo(workflow.id());

        int planNodeCount = countPlanNodes(plan.nodes());
        int planConditionalCount = countPlanNodesOfType(plan.nodes(), WorkflowStepType.CONDITIONAL);
        int planLoopCount = countPlanNodesOfType(plan.nodes(), WorkflowStepType.LOOP);
        int planParallelCount = countPlanNodesOfType(plan.nodes(), WorkflowStepType.PARALLEL);
        int planActionCount = countPlanNodesOfType(plan.nodes(), WorkflowStepType.ACTION);
        int planDeclaredOutputs = countDeclaredOutputs(plan.nodes());

        // --- Builder/Planner vs Introspector (sections 8-9) ---
        WorkflowIntrospectionReport report = new WorkflowIntrospector().inspect(workflow);
        assertThat(report.workflowId()).isEqualTo(workflow.id());
        assertThat(report.definitionNodeCount()).isEqualTo(planNodeCount);
        assertThat(report.conditionalCount()).isEqualTo(planConditionalCount);
        assertThat(report.loopCount()).isEqualTo(planLoopCount);
        assertThat(report.parallelCount()).isEqualTo(planParallelCount);
        assertThat(report.actionCount()).isEqualTo(planActionCount);
        assertThat(report.declaredOutputCount()).isEqualTo(planDeclaredOutputs);
        assertThat(report.maximumControlFlowDepth()).isEqualTo(3);
        assertThat(report.maximumLoopIterations()).isEqualTo(5);
        assertThat(report.maximumParallelBranches()).isEqualTo(2);
        assertThat(report.totalParallelBranches()).isEqualTo(2);
        assertThat(report.containsSecrets()).isTrue();
        assertThat(report.secretOutputCount()).isEqualTo(1);

        // Definite assignment: only the unconditional leaf's output is guaranteed. The loop body's
        // parallel outputs are never definite (the loop may run zero iterations), and the
        // conditional's own outer-level definite set only ever gains an output both THEN and ELSE
        // newly and directly introduce identically - here neither branch directly introduces
        // anything at the conditional's own level (THEN's only direct child is the loop, ELSE's is
        // "simple"), so nothing from either branch is promoted.
        assertThat(report.definitelyAvailableOutputCount()).isEqualTo(1);
        WorkflowIntrospectionOutput secretOutput =
                report.outputs().stream()
                        .filter(o -> o.name().equals("secretOut"))
                        .findFirst()
                        .orElseThrow();
        assertThat(secretOutput.definitelyAvailable()).isTrue();
        assertThat(secretOutput.secret()).isTrue();
        for (String neverDefinite : List.of("p0out", "p1out", "simpleOut")) {
            WorkflowIntrospectionOutput output =
                    report.outputs().stream()
                            .filter(o -> o.name().equals(neverDefinite))
                            .findFirst()
                            .orElseThrow();
            assertThat(output.definitelyAvailable())
                    .as("%s must not be definitely available", neverDefinite)
                    .isFalse();
        }

        // --- Engine vs Tree (section 27): flatten(tree) == result.steps(), by reference ---
        WorkflowExecution execution =
                new WorkflowEngine()
                        .executeWithTree(
                                workflow, WorkflowInputs.builder().put(outerFlag, true).build());
        assertThat(execution.result().completed()).isTrue();
        assertThat(execution.result().output(secretOut)).contains(secretSentinel);

        List<WorkflowStepResult> flattened = new ArrayList<>();
        flattenInto(execution.tree().nodes(), flattened);
        assertThat(flattened).hasSameSizeAs(execution.result().steps());
        for (int i = 0; i < flattened.size(); i++) {
            assertThat(flattened.get(i)).isSameAs(execution.result().steps().get(i));
        }

        // --- Tree vs Recording (sections 28-29) ---
        WorkflowRecordingV2 recording =
                new WorkflowRecorderV2()
                        .record(
                                new RecordingId("rec-golden-1.3"),
                                Instant.parse("2026-01-01T00:00:00Z"),
                                plan,
                                execution);
        assertThat(recording.workflowId()).isEqualTo(workflow.id());
        assertThat(recording.plan()).isEqualTo(plan);

        JsonWorkflowRecordingV2Codec codec = new JsonWorkflowRecordingV2Codec();
        String encoded = codec.encode(recording);
        assertThat(encoded).doesNotContain(secretSentinel);
        assertThat(recording.toString()).doesNotContain(secretSentinel);
        WorkflowRecordingV2 decoded = codec.decode(encoded);
        assertThat(decoded).isEqualTo(recording);

        // --- Recording vs Replay (sections 30-31): the live workflow is still exactly compatible
        Optional<ReplayValidationFailure> replayFailure =
                ReplayValidator.validate(decoded, workflow);
        assertThat(replayFailure).isEmpty();
    }
}
