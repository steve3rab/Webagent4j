package io.webagent4j.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.RecordComponent;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Deterministic Workflow Execution Plan (PLAN-001..011): {@link WorkflowExecutionPlan} is a purely
 * structural, backend-neutral description of what a {@link Workflow} could execute - built entirely
 * from its definition, never by running it. See {@code docs/workflow.md#execution-plan}.
 */
class WorkflowExecutionPlanTest {

    private static final WorkflowVariable<String> PRODUCED =
            WorkflowVariable.publicValue("produced", String.class);
    private static final WorkflowVariable<String> SECRET_PRODUCED =
            WorkflowVariable.secret("secretProduced");
    private static final WorkflowVariable<Boolean> FLAG =
            WorkflowVariable.publicValue("flag", Boolean.class);

    /**
     * A condition that counts every {@code evaluate()} call - must never be invoked by planning.
     */
    private static final class CountingCondition implements IWorkflowCondition {
        private final AtomicInteger evaluations;

        CountingCondition(AtomicInteger evaluations) {
            this.evaluations = evaluations;
        }

        @Override
        public boolean evaluate(IWorkflowVariables variables) {
            evaluations.incrementAndGet();
            return true;
        }

        @Override
        public String describe() {
            return "counting";
        }

        @Override
        public Set<WorkflowVariable<?>> referencedVariables() {
            return Set.of();
        }
    }

    /**
     * An action factory that counts every {@code prepare()} call - must never be invoked either.
     */
    private static final class CountingFactory<R> implements IWorkflowActionFactory<R> {
        private final AtomicInteger prepareCalls;
        private final R value;

        CountingFactory(AtomicInteger prepareCalls, R value) {
            this.prepareCalls = prepareCalls;
            this.value = value;
        }

        @Override
        public io.webagent4j.action.IPreparedAction<R> prepare(IWorkflowVariables variables) {
            prepareCalls.incrementAndGet();
            return new FakePreparedAction<>(ActionResults.success(value), new AtomicInteger());
        }
    }

    private static IWorkflowStep countingStep(String id, AtomicInteger prepareCalls) {
        return WorkflowSteps.action(id, new CountingFactory<>(prepareCalls, "v"));
    }

    // --- PLAN-001: sequential -----------------------------------------------------------------

    @Test
    void plan001SequentialOrderMatchesDefinition() {
        Workflow workflow =
                Workflow.builder("wf")
                        .step(countingStep("a", new AtomicInteger()))
                        .step(countingStep("b", new AtomicInteger()))
                        .step(countingStep("c", new AtomicInteger()))
                        .build();

        WorkflowExecutionPlan plan = WorkflowPlanner.plan(workflow);

        assertThat(plan.workflowId()).isEqualTo(workflow.id());
        assertThat(plan.nodes().stream().map(n -> n.stepId().value()))
                .containsExactly("a", "b", "c");
        assertThat(plan.nodes())
                .allSatisfy(n -> assertThat(n.stepType()).isEqualTo(WorkflowStepType.ACTION));
    }

    // --- PLAN-002: ifElse - both branches represented, zero evaluations -----------------------

    @Test
    void plan002IfElseRepresentsBothBranchesWithoutEvaluatingTheCondition() {
        AtomicInteger evaluations = new AtomicInteger();
        Workflow workflow =
                Workflow.builder("wf")
                        .step(
                                WorkflowSteps.ifElse(
                                        "branch",
                                        new CountingCondition(evaluations),
                                        List.of(countingStep("a", new AtomicInteger())),
                                        List.of(countingStep("b", new AtomicInteger()))))
                        .build();

        WorkflowExecutionPlan plan = WorkflowPlanner.plan(workflow);

        WorkflowPlanNode conditional = plan.nodes().get(0);
        assertThat(conditional.stepType()).isEqualTo(WorkflowStepType.CONDITIONAL);
        assertThat(conditional.branches()).hasSize(2);
        assertThat(conditional.branches().get(0).kind()).isEqualTo(WorkflowBranchSelection.THEN);
        assertThat(conditional.branches().get(0).nodes().get(0).stepId().value()).isEqualTo("a");
        assertThat(conditional.branches().get(1).kind()).isEqualTo(WorkflowBranchSelection.ELSE);
        assertThat(conditional.branches().get(1).nodes().get(0).stepId().value()).isEqualTo("b");
        assertThat(evaluations).hasValue(0);
    }

    // --- PLAN-003: ifThen - THEN represented, no-op path is NONE, never invented ELSE ---------

    @Test
    void plan003IfThenRepresentsNoOpPathAsNoneNeverInventingElse() {
        AtomicInteger evaluations = new AtomicInteger();
        Workflow workflow =
                Workflow.builder("wf")
                        .step(
                                WorkflowSteps.ifThen(
                                        "branch",
                                        new CountingCondition(evaluations),
                                        List.of(countingStep("a", new AtomicInteger()))))
                        .build();

        WorkflowExecutionPlan plan = WorkflowPlanner.plan(workflow);

        WorkflowPlanNode conditional = plan.nodes().get(0);
        assertThat(conditional.branches().get(0).kind()).isEqualTo(WorkflowBranchSelection.THEN);
        assertThat(conditional.branches().get(0).nodes().get(0).stepId().value()).isEqualTo("a");
        assertThat(conditional.branches().get(1).kind()).isEqualTo(WorkflowBranchSelection.NONE);
        assertThat(conditional.branches().get(1).nodes()).isEmpty();
        assertThat(evaluations).hasValue(0);
    }

    // --- PLAN-004: nested branching - exact hierarchy, zero evaluations -----------------------

    @Test
    void plan004NestedBranchingPreservesExactHierarchyWithoutEvaluatingAnything() {
        AtomicInteger outerEval = new AtomicInteger();
        AtomicInteger innerEval = new AtomicInteger();
        IWorkflowStep innerConditional =
                WorkflowSteps.ifElse(
                        "inner",
                        new CountingCondition(innerEval),
                        List.of(countingStep("c", new AtomicInteger())),
                        List.of(countingStep("d", new AtomicInteger())));
        Workflow workflow =
                Workflow.builder("wf")
                        .step(
                                WorkflowSteps.ifThen(
                                        "outer",
                                        new CountingCondition(outerEval),
                                        List.of(
                                                countingStep("b", new AtomicInteger()),
                                                innerConditional)))
                        .build();

        WorkflowExecutionPlan plan = WorkflowPlanner.plan(workflow);

        WorkflowPlanNode outer = plan.nodes().get(0);
        List<WorkflowPlanNode> thenNodes = outer.branches().get(0).nodes();
        assertThat(thenNodes).hasSize(2);
        assertThat(thenNodes.get(0).stepId().value()).isEqualTo("b");
        WorkflowPlanNode inner = thenNodes.get(1);
        assertThat(inner.stepId().value()).isEqualTo("inner");
        assertThat(inner.branches().get(0).nodes().get(0).stepId().value()).isEqualTo("c");
        assertThat(inner.branches().get(1).nodes().get(0).stepId().value()).isEqualTo("d");
        assertThat(outerEval).hasValue(0);
        assertThat(innerEval).hasValue(0);
    }

    // --- PLAN-005: guarded step - marked conditionally executable, guard never evaluated ------

    @Test
    void plan005GuardedStepMarkedConditionallyExecutableGuardNeverEvaluated() {
        AtomicInteger evaluations = new AtomicInteger();
        Workflow workflow =
                Workflow.builder("wf")
                        .requiredInput(FLAG)
                        .step(
                                countingStep("a", new AtomicInteger())
                                        .when(WorkflowConditions.isTrue(FLAG)))
                        .step(countingStep("b", new AtomicInteger()))
                        .build();

        WorkflowExecutionPlan plan = WorkflowPlanner.plan(workflow);

        assertThat(plan.nodes().get(0).guarded()).isTrue();
        assertThat(plan.nodes().get(1).guarded()).isFalse();
        assertThat(evaluations).hasValue(0);
    }

    // --- PLAN-006: action side effects - zero backend invocations ------------------------------

    @Test
    void plan006PlanningNeverInvokesTheActionFactory() {
        AtomicInteger prepareCalls = new AtomicInteger();
        Workflow workflow =
                Workflow.builder("wf")
                        .step(
                                WorkflowSteps.ifElse(
                                        "branch",
                                        new CountingCondition(new AtomicInteger()),
                                        List.of(countingStep("a", prepareCalls)),
                                        List.of(countingStep("b", prepareCalls))))
                        .step(countingStep("c", prepareCalls))
                        .build();

        WorkflowPlanner.plan(workflow);

        assertThat(prepareCalls).hasValue(0);
    }

    // --- PLAN-007: action factory safety (same invariant, explicit real-factory scenario) -----

    @Test
    void plan007ActionFactoryIsNeverCalledDuringPlanning() {
        AtomicInteger prepareCalls = new AtomicInteger();
        Workflow workflow = Workflow.builder("wf").step(countingStep("a", prepareCalls)).build();

        WorkflowExecutionPlan plan = WorkflowPlanner.plan(workflow);

        assertThat(plan.nodes()).hasSize(1);
        assertThat(prepareCalls).hasValue(0);
    }

    // --- PLAN-008: secret output - classification present, value never appears ----------------

    @Test
    void plan008SecretOutputClassificationPresentValueNeverAppears() {
        String sentinel = "WA4J_PLAN_SENTINEL_991733";
        Workflow workflow =
                Workflow.builder("wf")
                        .step(
                                WorkflowSteps.action(
                                        "a",
                                        vars ->
                                                new FakePreparedAction<>(
                                                        ActionResults.success(sentinel),
                                                        new AtomicInteger()),
                                        SECRET_PRODUCED))
                        .build();

        WorkflowExecutionPlan plan = WorkflowPlanner.plan(workflow);

        WorkflowPlanOutput output = plan.nodes().get(0).declaredOutput().orElseThrow();
        assertThat(output.name()).isEqualTo("secretProduced");
        assertThat(output.secret()).isTrue();
        assertThat(output.typeName()).isEqualTo("String");
        assertThat(plan.toString()).doesNotContain(sentinel);
    }

    @Test
    void plan008PublicOutputClassificationIsNotSecret() {
        Workflow workflow =
                Workflow.builder("wf")
                        .step(
                                WorkflowSteps.action(
                                        "a",
                                        vars ->
                                                new FakePreparedAction<>(
                                                        ActionResults.success("v"),
                                                        new AtomicInteger()),
                                        PRODUCED))
                        .build();

        WorkflowExecutionPlan plan = WorkflowPlanner.plan(workflow);

        WorkflowPlanOutput output = plan.nodes().get(0).declaredOutput().orElseThrow();
        assertThat(output.secret()).isFalse();
    }

    // --- PLAN-009: maximum nesting depth - no StackOverflowError -------------------------------

    @Test
    void plan009MaximumNestingDepthBuildsWithoutStackOverflow() {
        int max = Workflow.MAX_CONDITIONAL_NESTING_DEPTH;
        IWorkflowStep current = countingStep("leaf", new AtomicInteger());
        for (int level = max; level >= 1; level--) {
            current =
                    WorkflowSteps.ifThen(
                            "d-" + level,
                            new CountingCondition(new AtomicInteger()),
                            List.of(current));
        }
        Workflow workflow = Workflow.builder("wf-max-depth").step(current).build();

        WorkflowExecutionPlan plan = WorkflowPlanner.plan(workflow);

        int depth = 0;
        List<WorkflowPlanNode> level = plan.nodes();
        while (!level.isEmpty()) {
            assertThat(level).hasSize(1);
            WorkflowPlanNode node = level.get(0);
            if (node.stepType() == WorkflowStepType.CONDITIONAL) {
                depth++;
                level = node.branches().get(0).nodes();
            } else {
                level = List.of();
            }
        }
        assertThat(depth).isEqualTo(max);
    }

    // --- PLAN-010: immutability -----------------------------------------------------------------

    @Test
    void plan010StructuralImmutability() {
        Workflow workflow =
                Workflow.builder("wf")
                        .step(
                                WorkflowSteps.ifThen(
                                        "branch",
                                        new CountingCondition(new AtomicInteger()),
                                        List.of(countingStep("a", new AtomicInteger()))))
                        .build();

        WorkflowExecutionPlan plan = WorkflowPlanner.plan(workflow);

        List<WorkflowPlanNode> nodes = plan.nodes();
        assertThatThrownBy(() -> nodes.add(nodes.get(0)))
                .isInstanceOf(UnsupportedOperationException.class);
        List<WorkflowPlanBranch> branches = nodes.get(0).branches();
        assertThatThrownBy(() -> branches.add(branches.get(0)))
                .isInstanceOf(UnsupportedOperationException.class);
        List<WorkflowPlanNode> branchNodes = branches.get(0).nodes();
        assertThatThrownBy(() -> branchNodes.add(branchNodes.get(0)))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    // --- PLAN-011: determinism ------------------------------------------------------------------

    @Test
    void plan011TwoPlansOfTheSameWorkflowAreLogicallyEqual() {
        Workflow workflow =
                Workflow.builder("wf")
                        .step(countingStep("a", new AtomicInteger()))
                        .step(
                                WorkflowSteps.ifElse(
                                        "branch",
                                        new CountingCondition(new AtomicInteger()),
                                        List.of(countingStep("b", new AtomicInteger())),
                                        List.of(countingStep("c", new AtomicInteger()))))
                        .build();

        WorkflowExecutionPlan plan1 = WorkflowPlanner.plan(workflow);
        WorkflowExecutionPlan plan2 = WorkflowPlanner.plan(workflow);

        assertThat(plan1).isEqualTo(plan2);
        assertThat(plan1.nodes()).isEqualTo(plan2.nodes());
    }

    // --- no backend object retention (structural review) ----------------------------------------

    @Test
    void planTypesNeverRetainABackendObjectType() {
        for (Class<?> type :
                List.of(
                        WorkflowExecutionPlan.class,
                        WorkflowPlanNode.class,
                        WorkflowPlanBranch.class,
                        WorkflowPlanOutput.class)) {
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
                        .doesNotContain("Throwable")
                        .doesNotContain("Thread")
                        .doesNotContain("Executor");
            }
        }
    }

    // --- policy outcome is never invented (no such field exists at all) -------------------------

    @Test
    void planNodeNeverExposesAnInventedPolicyOutcome() {
        for (RecordComponent component : WorkflowPlanNode.class.getRecordComponents()) {
            assertThat(component.getName().toLowerCase(java.util.Locale.ROOT))
                    .as("WorkflowPlanNode must never claim a policy outcome it cannot know")
                    .doesNotContain("policy")
                    .doesNotContain("allowed")
                    .doesNotContain("denied");
        }
    }
}
