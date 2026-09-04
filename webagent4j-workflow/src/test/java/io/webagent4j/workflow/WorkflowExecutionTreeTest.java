package io.webagent4j.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.webagent4j.action.ActionFailureType;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Structured Execution Tree (TREE-001..020): {@link WorkflowExecutionTree} is a lossless
 * hierarchical view of the workflow execution results the engine already produced, built once,
 * during the same single execution pass that produces {@link WorkflowResult#steps()} - never a
 * second interpretation, never a second condition evaluation, never a second action invocation. See
 * {@code docs/workflow.md#execution-tree}.
 */
class WorkflowExecutionTreeTest {

    private static final String SECRET_SENTINEL = "WA4J_TREE_SECRET_402117";
    private static final WorkflowVariable<Boolean> FLAG =
            WorkflowVariable.publicValue("flag", Boolean.class);
    private static final WorkflowVariable<String> PRODUCED =
            WorkflowVariable.publicValue("produced", String.class);
    private static final WorkflowVariable<String> SECRET_PRODUCED =
            WorkflowVariable.secret("secretProduced");

    private final WorkflowEngine engine = new WorkflowEngine();

    @AfterEach
    void clearInterruptFlag() {
        Thread.interrupted();
    }

    // --- shared helpers -------------------------------------------------------------------

    private static IWorkflowStep successStep(String id, AtomicInteger counter) {
        return WorkflowSteps.action(
                id, variables -> new FakePreparedAction<>(ActionResults.success("v"), counter));
    }

    private static IWorkflowStep failingStep(String id, AtomicInteger counter) {
        return WorkflowSteps.action(
                id,
                variables ->
                        new FakePreparedAction<>(
                                ActionResults.failure(ActionFailureType.TARGET_NOT_FOUND, "boom"),
                                counter));
    }

    private static IWorkflowStep producingStep(
            String id, WorkflowVariable<String> output, AtomicInteger counter) {
        return WorkflowSteps.action(
                id,
                variables -> new FakePreparedAction<>(ActionResults.success("v"), counter),
                output);
    }

    private static final class CountingCondition implements IWorkflowCondition {
        private final boolean outcome;
        private final AtomicInteger evaluations;

        CountingCondition(boolean outcome, AtomicInteger evaluations) {
            this.outcome = outcome;
            this.evaluations = evaluations;
        }

        @Override
        public boolean evaluate(IWorkflowVariables variables) {
            evaluations.incrementAndGet();
            return outcome;
        }

        @Override
        public String describe() {
            return "counting(" + outcome + ")";
        }

        @Override
        public Set<WorkflowVariable<?>> referencedVariables() {
            return Set.of();
        }
    }

    private static final class ThrowingCondition implements IWorkflowCondition {
        @Override
        public boolean evaluate(IWorkflowVariables variables) {
            throw new IllegalStateException("condition boom");
        }

        @Override
        public String describe() {
            return "throwing";
        }

        @Override
        public Set<WorkflowVariable<?>> referencedVariables() {
            return Set.of();
        }
    }

    /**
     * Flattens {@code tree} in execution order - test-only, mirroring exactly how {@link
     * WorkflowEngine} itself builds the flat list, used solely to prove the flat/tree equivalence
     * invariant below. Never used by production code.
     */
    private static List<WorkflowStepResult> flatten(WorkflowExecutionTree tree) {
        List<WorkflowStepResult> flat = new ArrayList<>();
        flattenInto(tree.nodes(), flat);
        return flat;
    }

    private static void flattenInto(
            List<WorkflowExecutionNode> nodes, List<WorkflowStepResult> flat) {
        for (WorkflowExecutionNode node : nodes) {
            flat.add(node.result());
            flattenInto(node.children(), flat);
        }
    }

    private static void assertFlattenMatchesResult(WorkflowExecution execution) {
        assertThat(flatten(execution.tree())).isEqualTo(execution.result().steps());
    }

    // --- TREE-001: sequential success -------------------------------------------------------

    @Test
    void tree001SequentialSuccess() {
        Workflow workflow =
                Workflow.builder("wf")
                        .step(successStep("a", new AtomicInteger()))
                        .step(successStep("b", new AtomicInteger()))
                        .step(successStep("c", new AtomicInteger()))
                        .build();

        WorkflowExecution execution = engine.executeWithTree(workflow, WorkflowInputs.empty());

        assertThat(execution.tree().nodes()).hasSize(3);
        assertThat(execution.tree().nodes().stream().map(n -> n.result().stepId().value()))
                .containsExactly("a", "b", "c");
        assertThat(execution.tree().nodes())
                .allSatisfy(
                        node -> {
                            assertThat(node.result().status())
                                    .isEqualTo(WorkflowStepStatus.SUCCEEDED);
                            assertThat(node.children()).isEmpty();
                            assertThat(node.branchSelection()).isEmpty();
                        });
        assertFlattenMatchesResult(execution);
    }

    // --- TREE-002: guard skipped -------------------------------------------------------------

    @Test
    void tree002GuardSkipped() {
        Workflow workflow =
                Workflow.builder("wf")
                        .requiredInput(FLAG)
                        .step(
                                successStep("a", new AtomicInteger())
                                        .when(WorkflowConditions.isTrue(FLAG)))
                        .step(successStep("b", new AtomicInteger()))
                        .build();
        WorkflowInputs inputs = WorkflowInputs.builder().put(FLAG, false).build();

        WorkflowExecution execution = engine.executeWithTree(workflow, inputs);

        assertThat(execution.tree().nodes()).hasSize(2);
        WorkflowExecutionNode a = execution.tree().nodes().get(0);
        assertThat(a.result().stepId().value()).isEqualTo("a");
        assertThat(a.result().status()).isEqualTo(WorkflowStepStatus.SKIPPED);
        assertThat(a.children()).isEmpty();
        WorkflowExecutionNode b = execution.tree().nodes().get(1);
        assertThat(b.result().status()).isEqualTo(WorkflowStepStatus.SUCCEEDED);
        assertFlattenMatchesResult(execution);
    }

    // --- TREE-003 / TREE-004: ifElse THEN / ELSE ---------------------------------------------

    @Test
    void tree003IfElseThenSelected() {
        AtomicInteger evaluations = new AtomicInteger();
        AtomicInteger thenCalls = new AtomicInteger();
        AtomicInteger elseCalls = new AtomicInteger();
        Workflow workflow =
                Workflow.builder("wf")
                        .step(
                                WorkflowSteps.ifElse(
                                        "branch",
                                        new CountingCondition(true, evaluations),
                                        List.of(successStep("a", thenCalls)),
                                        List.of(successStep("b", elseCalls))))
                        .step(successStep("c", new AtomicInteger()))
                        .build();

        WorkflowExecution execution = engine.executeWithTree(workflow, WorkflowInputs.empty());

        assertThat(execution.tree().nodes()).hasSize(2);
        WorkflowExecutionNode conditional = execution.tree().nodes().get(0);
        assertThat(conditional.result().stepType()).isEqualTo(WorkflowStepType.CONDITIONAL);
        assertThat(conditional.branchSelection()).contains(WorkflowBranchSelection.THEN);
        assertThat(conditional.children()).hasSize(1);
        assertThat(conditional.children().get(0).result().stepId().value()).isEqualTo("a");
        assertThat(execution.tree().nodes().get(1).result().stepId().value()).isEqualTo("c");
        assertThat(elseCalls).hasValue(0);
        assertThat(evaluations).hasValue(1);
        assertFlattenMatchesResult(execution);
    }

    @Test
    void tree004IfElseElseSelected() {
        AtomicInteger evaluations = new AtomicInteger();
        AtomicInteger thenCalls = new AtomicInteger();
        AtomicInteger elseCalls = new AtomicInteger();
        Workflow workflow =
                Workflow.builder("wf")
                        .step(
                                WorkflowSteps.ifElse(
                                        "branch",
                                        new CountingCondition(false, evaluations),
                                        List.of(successStep("a", thenCalls)),
                                        List.of(successStep("b", elseCalls))))
                        .build();

        WorkflowExecution execution = engine.executeWithTree(workflow, WorkflowInputs.empty());

        WorkflowExecutionNode conditional = execution.tree().nodes().get(0);
        assertThat(conditional.branchSelection()).contains(WorkflowBranchSelection.ELSE);
        assertThat(conditional.children()).hasSize(1);
        assertThat(conditional.children().get(0).result().stepId().value()).isEqualTo("b");
        assertThat(thenCalls).hasValue(0);
        assertFlattenMatchesResult(execution);
    }

    // --- TREE-005: ifThen false -> NONE, never ELSE ------------------------------------------

    @Test
    void tree005IfThenFalseSelectsNone() {
        AtomicInteger evaluations = new AtomicInteger();
        AtomicInteger thenCalls = new AtomicInteger();
        Workflow workflow =
                Workflow.builder("wf")
                        .step(
                                WorkflowSteps.ifThen(
                                        "branch",
                                        new CountingCondition(false, evaluations),
                                        List.of(successStep("a", thenCalls))))
                        .step(successStep("b", new AtomicInteger()))
                        .build();

        WorkflowExecution execution = engine.executeWithTree(workflow, WorkflowInputs.empty());

        WorkflowExecutionNode conditional = execution.tree().nodes().get(0);
        assertThat(conditional.branchSelection()).contains(WorkflowBranchSelection.NONE);
        assertThat(conditional.children()).isEmpty();
        assertThat(conditional.result().status()).isEqualTo(WorkflowStepStatus.SUCCEEDED);
        assertThat(thenCalls).hasValue(0);
        assertThat(execution.tree().nodes().get(1).result().stepId().value()).isEqualTo("b");
        assertFlattenMatchesResult(execution);
    }

    // --- TREE-006: condition evaluation failure ----------------------------------------------

    @Test
    void tree006ConditionEvaluationFailure() {
        Workflow workflow =
                Workflow.builder("wf")
                        .step(
                                WorkflowSteps.ifElse(
                                        "branch",
                                        new ThrowingCondition(),
                                        List.of(successStep("a", new AtomicInteger())),
                                        List.of(successStep("b", new AtomicInteger()))))
                        .step(successStep("c", new AtomicInteger()))
                        .build();

        WorkflowExecution execution = engine.executeWithTree(workflow, WorkflowInputs.empty());

        assertThat(execution.result().completed()).isFalse();
        WorkflowExecutionNode conditional = execution.tree().nodes().get(0);
        assertThat(conditional.result().status()).isEqualTo(WorkflowStepStatus.FAILED);
        assertThat(conditional.result().failure().orElseThrow().type())
                .isEqualTo(WorkflowFailureType.CONDITION_EVALUATION_FAILED);
        assertThat(conditional.branchSelection()).isEmpty();
        assertThat(conditional.children()).isEmpty();
        WorkflowExecutionNode after = execution.tree().nodes().get(1);
        assertThat(after.result().status()).isEqualTo(WorkflowStepStatus.NOT_RUN);
        assertFlattenMatchesResult(execution);
    }

    // --- TREE-007: selected branch failure, NOT_RUN inside branch and after conditional ------

    @Test
    void tree007SelectedBranchFailurePropagatesNotRun() {
        AtomicInteger evaluations = new AtomicInteger();
        AtomicInteger aCalls = new AtomicInteger();
        AtomicInteger cCalls = new AtomicInteger();
        AtomicInteger dCalls = new AtomicInteger();
        Workflow workflow =
                Workflow.builder("wf")
                        .step(
                                WorkflowSteps.ifElse(
                                        "branch",
                                        new CountingCondition(true, evaluations),
                                        List.of(
                                                successStep("a", aCalls),
                                                failingStep("b", new AtomicInteger()),
                                                successStep("c", cCalls)),
                                        List.of(successStep("d", dCalls))))
                        .step(successStep("e", new AtomicInteger()))
                        .build();

        WorkflowExecution execution = engine.executeWithTree(workflow, WorkflowInputs.empty());

        assertThat(execution.result().completed()).isFalse();
        WorkflowExecutionNode conditional = execution.tree().nodes().get(0);
        assertThat(conditional.branchSelection()).contains(WorkflowBranchSelection.THEN);
        assertThat(conditional.children()).hasSize(3);
        assertThat(conditional.children().get(0).result().stepId().value()).isEqualTo("a");
        assertThat(conditional.children().get(0).result().status())
                .isEqualTo(WorkflowStepStatus.SUCCEEDED);
        assertThat(conditional.children().get(1).result().stepId().value()).isEqualTo("b");
        assertThat(conditional.children().get(1).result().status())
                .isEqualTo(WorkflowStepStatus.FAILED);
        assertThat(conditional.children().get(2).result().stepId().value()).isEqualTo("c");
        assertThat(conditional.children().get(2).result().status())
                .isEqualTo(WorkflowStepStatus.NOT_RUN);
        WorkflowExecutionNode e = execution.tree().nodes().get(1);
        assertThat(e.result().stepId().value()).isEqualTo("e");
        assertThat(e.result().status()).isEqualTo(WorkflowStepStatus.NOT_RUN);
        assertThat(dCalls).hasValue(0);
        assertThat(cCalls).hasValue(0);
        assertFlattenMatchesResult(execution);
    }

    // --- TREE-008: nested branching -----------------------------------------------------------

    @Test
    void tree008NestedBranching() {
        AtomicInteger outerEval = new AtomicInteger();
        AtomicInteger innerEval = new AtomicInteger();
        AtomicInteger bCalls = new AtomicInteger();
        AtomicInteger dCalls = new AtomicInteger();
        IWorkflowStep innerConditional =
                WorkflowSteps.ifElse(
                        "inner",
                        new CountingCondition(false, innerEval),
                        List.of(successStep("c", new AtomicInteger())),
                        List.of(successStep("d", dCalls)));
        Workflow workflow =
                Workflow.builder("wf")
                        .step(
                                WorkflowSteps.ifThen(
                                        "outer",
                                        new CountingCondition(true, outerEval),
                                        List.of(successStep("b", bCalls), innerConditional)))
                        .build();

        WorkflowExecution execution = engine.executeWithTree(workflow, WorkflowInputs.empty());

        assertThat(outerEval).hasValue(1);
        assertThat(innerEval).hasValue(1);
        WorkflowExecutionNode outer = execution.tree().nodes().get(0);
        assertThat(outer.branchSelection()).contains(WorkflowBranchSelection.THEN);
        assertThat(outer.children()).hasSize(2);
        assertThat(outer.children().get(0).result().stepId().value()).isEqualTo("b");
        WorkflowExecutionNode inner = outer.children().get(1);
        assertThat(inner.result().stepId().value()).isEqualTo("inner");
        assertThat(inner.branchSelection()).contains(WorkflowBranchSelection.ELSE);
        assertThat(inner.children()).hasSize(1);
        assertThat(inner.children().get(0).result().stepId().value()).isEqualTo("d");
        assertThat(dCalls).hasValue(1);
        assertFlattenMatchesResult(execution);
    }

    // --- TREE-009: maximum nesting depth -------------------------------------------------------

    @Test
    void tree009MaximumNestingDepthBuildsAndExecutesWithoutStackOverflow() {
        int max = Workflow.MAX_CONDITIONAL_NESTING_DEPTH;
        AtomicInteger evaluations = new AtomicInteger();
        AtomicInteger leafCalls = new AtomicInteger();
        IWorkflowStep current = successStep("leaf", leafCalls);
        for (int level = max; level >= 1; level--) {
            current =
                    WorkflowSteps.ifThen(
                            "d-" + level,
                            new CountingCondition(true, evaluations),
                            List.of(current));
        }
        Workflow workflow = Workflow.builder("wf-max-depth").step(current).build();

        WorkflowExecution execution = engine.executeWithTree(workflow, WorkflowInputs.empty());

        assertThat(execution.result().completed()).isTrue();
        assertThat(evaluations).hasValue(max);
        assertThat(leafCalls).hasValue(1);

        int depth = 0;
        List<WorkflowExecutionNode> level = execution.tree().nodes();
        while (!level.isEmpty()) {
            assertThat(level).hasSize(1);
            WorkflowExecutionNode node = level.get(0);
            if (node.result().stepType() == WorkflowStepType.CONDITIONAL) {
                depth++;
                level = node.children();
            } else {
                level = List.of();
            }
        }
        assertThat(depth).isEqualTo(max);
        assertFlattenMatchesResult(execution);
    }

    // --- TREE-010 / 011 / 012: output publication accuracy -------------------------------------

    @Test
    void tree010OutputPublicationVisibleOnSuccessNode() {
        Workflow workflow =
                Workflow.builder("wf")
                        .step(producingStep("a", PRODUCED, new AtomicInteger()))
                        .build();

        WorkflowExecution execution = engine.executeWithTree(workflow, WorkflowInputs.empty());

        WorkflowExecutionNode a = execution.tree().nodes().get(0);
        assertThat(a.result().outputVariableName()).contains("produced");
        assertFlattenMatchesResult(execution);
    }

    @Test
    void tree011SkippedProducerNeverShowsOutputPublication() {
        Workflow workflow =
                Workflow.builder("wf")
                        .requiredInput(FLAG)
                        .step(
                                producingStep("a", PRODUCED, new AtomicInteger())
                                        .when(WorkflowConditions.isTrue(FLAG)))
                        .build();
        WorkflowInputs inputs = WorkflowInputs.builder().put(FLAG, false).build();

        WorkflowExecution execution = engine.executeWithTree(workflow, inputs);

        WorkflowExecutionNode a = execution.tree().nodes().get(0);
        assertThat(a.result().status()).isEqualTo(WorkflowStepStatus.SKIPPED);
        assertThat(a.result().outputVariableName()).isEmpty();
        assertFlattenMatchesResult(execution);
    }

    @Test
    void tree012FailedProducerNeverShowsOutputPublication() {
        Workflow workflow =
                Workflow.builder("wf")
                        .step(
                                WorkflowSteps.action(
                                        "a",
                                        variables ->
                                                new FakePreparedAction<>(
                                                        ActionResults.<String>failure(
                                                                ActionFailureType.TARGET_NOT_FOUND,
                                                                "boom"),
                                                        new AtomicInteger()),
                                        PRODUCED))
                        .build();

        WorkflowExecution execution = engine.executeWithTree(workflow, WorkflowInputs.empty());

        WorkflowExecutionNode a = execution.tree().nodes().get(0);
        assertThat(a.result().status()).isEqualTo(WorkflowStepStatus.FAILED);
        assertThat(a.result().outputVariableName()).isEmpty();
        assertFlattenMatchesResult(execution);
    }

    // --- TREE-013: secret output never appears raw in the tree ---------------------------------

    @Test
    void tree013SecretOutputNeverAppearsRawAnywhereInTheTree() {
        Workflow workflow =
                Workflow.builder("wf")
                        .step(
                                WorkflowSteps.action(
                                        "a",
                                        variables ->
                                                new FakePreparedAction<>(
                                                        ActionResults.success(SECRET_SENTINEL),
                                                        new AtomicInteger()),
                                        SECRET_PRODUCED))
                        .step(
                                WorkflowSteps.action(
                                        "b",
                                        variables -> {
                                            throw new RuntimeException(
                                                    "leak attempt "
                                                            + variables.require(SECRET_PRODUCED));
                                        }))
                        .build();

        WorkflowExecution execution = engine.executeWithTree(workflow, WorkflowInputs.empty());

        assertThat(execution.tree().toString()).doesNotContain(SECRET_SENTINEL);
        for (WorkflowExecutionNode node : execution.tree().nodes()) {
            assertThat(node.toString()).doesNotContain(SECRET_SENTINEL);
            assertThat(node.result().toString()).doesNotContain(SECRET_SENTINEL);
        }
        assertFlattenMatchesResult(execution);
    }

    // --- TREE-015: exactly-once action invocation reflected as exactly one action node ------

    @Test
    void tree015SelectedActionInvokedExactlyOnceAndAppearsAsExactlyOneNode() {
        AtomicInteger evaluations = new AtomicInteger();
        AtomicInteger clickInvocations = new AtomicInteger();
        Workflow workflow =
                Workflow.builder("wf")
                        .step(
                                WorkflowSteps.ifElse(
                                        "branch",
                                        new CountingCondition(true, evaluations),
                                        List.of(successStep("click", clickInvocations)),
                                        List.of(successStep("other-click", new AtomicInteger()))))
                        .build();

        WorkflowExecution execution = engine.executeWithTree(workflow, WorkflowInputs.empty());

        assertThat(clickInvocations).hasValue(1);
        WorkflowExecutionNode conditional = execution.tree().nodes().get(0);
        assertThat(conditional.children()).hasSize(1);
        assertThat(conditional.children().get(0).result().stepId().value()).isEqualTo("click");
        assertFlattenMatchesResult(execution);
    }

    // --- TREE-016: result stability --------------------------------------------------------

    @Test
    void tree016ResultStabilityAcrossRepeatedReads() {
        Workflow workflow =
                Workflow.builder("wf").step(successStep("a", new AtomicInteger())).build();

        WorkflowExecution execution = engine.executeWithTree(workflow, WorkflowInputs.empty());

        WorkflowExecutionTree firstRead = execution.tree();
        WorkflowExecutionTree secondRead = execution.tree();
        assertThat(firstRead).isEqualTo(secondRead);
        assertThat(firstRead.nodes()).isEqualTo(secondRead.nodes());
    }

    // --- TREE-017: execution isolation ------------------------------------------------------

    @Test
    void tree017ExecutionIsolationBetweenRuns() {
        Workflow workflow =
                Workflow.builder("wf")
                        .requiredInput(FLAG)
                        .step(
                                WorkflowSteps.ifElse(
                                        "branch",
                                        WorkflowConditions.isTrue(FLAG),
                                        List.of(successStep("a", new AtomicInteger())),
                                        List.of(successStep("b", new AtomicInteger()))))
                        .build();

        WorkflowExecution first =
                engine.executeWithTree(workflow, WorkflowInputs.builder().put(FLAG, true).build());
        WorkflowExecution second =
                engine.executeWithTree(workflow, WorkflowInputs.builder().put(FLAG, false).build());

        assertThat(first.tree().nodes().get(0).branchSelection())
                .contains(WorkflowBranchSelection.THEN);
        assertThat(second.tree().nodes().get(0).branchSelection())
                .contains(WorkflowBranchSelection.ELSE);
        assertThat(first.tree()).isNotEqualTo(second.tree());
    }

    // --- TREE-018: structural immutability --------------------------------------------------

    @Test
    void tree018StructuralImmutability() {
        AtomicInteger evaluations = new AtomicInteger();
        Workflow workflow =
                Workflow.builder("wf")
                        .step(
                                WorkflowSteps.ifThen(
                                        "branch",
                                        new CountingCondition(true, evaluations),
                                        List.of(successStep("a", new AtomicInteger()))))
                        .build();

        WorkflowExecution execution = engine.executeWithTree(workflow, WorkflowInputs.empty());

        List<WorkflowExecutionNode> nodes = execution.tree().nodes();
        assertThatThrownBy(() -> nodes.add(nodes.get(0)))
                .isInstanceOf(UnsupportedOperationException.class);
        List<WorkflowExecutionNode> children = nodes.get(0).children();
        assertThatThrownBy(() -> children.add(children.get(0)))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    // --- TREE-019: no backend object retention (structural review) --------------------------

    @Test
    void tree019NoBackendObjectRetentionInNewExecutionTreeTypes() {
        for (Class<?> type :
                List.of(
                        WorkflowExecution.class,
                        WorkflowExecutionTree.class,
                        WorkflowExecutionNode.class)) {
            for (RecordComponent component : type.getRecordComponents()) {
                String typeName = component.getType().getName();
                assertThat(typeName)
                        .as(
                                "record component '%s' on %s",
                                component.getName(), type.getSimpleName())
                        .doesNotContain("browser")
                        .doesNotContain("Page")
                        .doesNotContain("Locator")
                        .doesNotContain("Element")
                        .doesNotContain("PreparedAction")
                        .doesNotContain("ActionBuilder")
                        .doesNotContain("Throwable");
            }
        }
    }

    // --- TREE-020: parameterized flat/tree equivalence matrix --------------------------------

    @Test
    void tree020FlatTreeEquivalenceAcrossScenarios() {
        // Re-runs a representative slice of the scenarios above through the shared invariant
        // assertion, as one consolidated matrix check.
        assertFlattenMatchesResult(
                engine.executeWithTree(
                        Workflow.builder("seq")
                                .step(successStep("a", new AtomicInteger()))
                                .step(failingStep("b", new AtomicInteger()))
                                .step(successStep("c", new AtomicInteger()))
                                .build(),
                        WorkflowInputs.empty()));

        AtomicInteger eval1 = new AtomicInteger();
        assertFlattenMatchesResult(
                engine.executeWithTree(
                        Workflow.builder("branch-then")
                                .step(
                                        WorkflowSteps.ifElse(
                                                "branch",
                                                new CountingCondition(true, eval1),
                                                List.of(successStep("a", new AtomicInteger())),
                                                List.of(successStep("b", new AtomicInteger()))))
                                .build(),
                        WorkflowInputs.empty()));

        AtomicInteger eval2 = new AtomicInteger();
        assertFlattenMatchesResult(
                engine.executeWithTree(
                        Workflow.builder("branch-else")
                                .step(
                                        WorkflowSteps.ifElse(
                                                "branch",
                                                new CountingCondition(false, eval2),
                                                List.of(successStep("a", new AtomicInteger())),
                                                List.of(successStep("b", new AtomicInteger()))))
                                .build(),
                        WorkflowInputs.empty()));

        AtomicInteger eval3 = new AtomicInteger();
        assertFlattenMatchesResult(
                engine.executeWithTree(
                        Workflow.builder("if-then-false")
                                .step(
                                        WorkflowSteps.ifThen(
                                                "branch",
                                                new CountingCondition(false, eval3),
                                                List.of(successStep("a", new AtomicInteger()))))
                                .build(),
                        WorkflowInputs.empty()));

        assertFlattenMatchesResult(
                engine.executeWithTree(
                        Workflow.builder("preflight")
                                .requiredInput(FLAG)
                                .step(successStep("a", new AtomicInteger()))
                                .build(),
                        WorkflowInputs.empty()));
    }

    // --- interruption boundaries (adversarial coverage, sections 15/16) ----------------------

    @Test
    void interruptedBeforeConditionEvaluationLeavesNoDecisionAndNoChildren() {
        AtomicInteger evaluations = new AtomicInteger();
        Workflow workflow =
                Workflow.builder("wf")
                        .step(
                                WorkflowSteps.ifElse(
                                        "branch",
                                        new CountingCondition(true, evaluations),
                                        List.of(successStep("a", new AtomicInteger())),
                                        List.of(successStep("b", new AtomicInteger()))))
                        .build();

        Thread.currentThread().interrupt();
        WorkflowExecution execution = engine.executeWithTree(workflow, WorkflowInputs.empty());

        WorkflowExecutionNode conditional = execution.tree().nodes().get(0);
        assertThat(conditional.result().status()).isEqualTo(WorkflowStepStatus.FAILED);
        assertThat(conditional.result().failure().orElseThrow().type())
                .isEqualTo(WorkflowFailureType.CONDITIONAL_STEP_INTERRUPTED);
        assertThat(conditional.branchSelection()).isEmpty();
        assertThat(conditional.children()).isEmpty();
        assertThat(evaluations).hasValue(0);
        assertFlattenMatchesResult(execution);
    }

    @Test
    void interruptedAfterDecisionBeforeBranchStartsKeepsTheSelectionButNoChildren() {
        AtomicInteger evaluations = new AtomicInteger();
        IWorkflowCondition interruptAfterDeciding =
                new IWorkflowCondition() {
                    @Override
                    public boolean evaluate(IWorkflowVariables variables) {
                        evaluations.incrementAndGet();
                        Thread.currentThread().interrupt();
                        return true;
                    }

                    @Override
                    public String describe() {
                        return "interruptAfterDeciding";
                    }

                    @Override
                    public Set<WorkflowVariable<?>> referencedVariables() {
                        return Set.of();
                    }
                };
        Workflow workflow =
                Workflow.builder("wf")
                        .step(
                                WorkflowSteps.ifElse(
                                        "branch",
                                        interruptAfterDeciding,
                                        List.of(successStep("a", new AtomicInteger())),
                                        List.of(successStep("b", new AtomicInteger()))))
                        .build();

        WorkflowExecution execution = engine.executeWithTree(workflow, WorkflowInputs.empty());

        WorkflowExecutionNode conditional = execution.tree().nodes().get(0);
        assertThat(conditional.result().failure().orElseThrow().type())
                .isEqualTo(WorkflowFailureType.CONDITIONAL_STEP_INTERRUPTED);
        // The decision was captured (THEN) before the interrupt was observed, but the branch
        // itself never started - exactly the distinction sections 15/16 require the tree draw.
        assertThat(conditional.branchSelection()).contains(WorkflowBranchSelection.THEN);
        assertThat(conditional.children()).isEmpty();
        assertFlattenMatchesResult(execution);
    }
}
