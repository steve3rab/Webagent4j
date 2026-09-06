package io.webagent4j.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Static Workflow Introspection (INTROSPECT-001..020, SAT-001..006, BUDGET-001..005): {@link
 * WorkflowIntrospector#inspect(Workflow)} produces a deterministic, backend-neutral complexity and
 * safety-surface summary of an already-valid {@link Workflow} definition without ever executing
 * anything - never a condition evaluation, never an action factory call, never a backend/browser/
 * network interaction, never a thread. See {@code docs/workflow.md#static-workflow-introspection}.
 */
class WorkflowIntrospectorTest {

    private final WorkflowIntrospector introspector = new WorkflowIntrospector();

    /**
     * A condition that counts every {@code evaluate()}/{@code describe()} call - must stay at 0.
     */
    private static final class CountingCondition implements IWorkflowCondition {
        private final AtomicInteger evaluations;
        private final boolean outcome;

        CountingCondition(AtomicInteger evaluations, boolean outcome) {
            this.evaluations = evaluations;
            this.outcome = outcome;
        }

        @Override
        public boolean evaluate(IWorkflowVariables variables) {
            evaluations.incrementAndGet();
            return outcome;
        }

        @Override
        public String describe() {
            evaluations.incrementAndGet();
            return "counting";
        }

        @Override
        public Set<WorkflowVariable<?>> referencedVariables() {
            return Set.of();
        }
    }

    /** A condition that throws if ever evaluated or described - a louder proof than counting. */
    private static final IWorkflowCondition NEVER_EVALUATED =
            new IWorkflowCondition() {
                @Override
                public boolean evaluate(IWorkflowVariables variables) {
                    throw new AssertionError("must not evaluate during static introspection");
                }

                @Override
                public String describe() {
                    throw new AssertionError("must not describe during static introspection");
                }

                @Override
                public Set<WorkflowVariable<?>> referencedVariables() {
                    return Set.of();
                }
            };

    /** An action factory that counts every {@code prepare()} call - must stay at 0. */
    private static IWorkflowStep countingAction(String id, AtomicInteger prepareCalls) {
        return WorkflowSteps.action(
                id,
                variables -> {
                    prepareCalls.incrementAndGet();
                    throw new AssertionError("must not execute during static introspection");
                });
    }

    /** An action step whose factory throws if ever invoked - the default leaf fixture. */
    private static IWorkflowStep neverRunAction(String id) {
        return WorkflowSteps.action(
                id,
                variables -> {
                    throw new AssertionError(
                            "action factory must never be invoked during"
                                    + " static introspection");
                });
    }

    private static IWorkflowStep neverRunAction(String id, WorkflowVariable<String> output) {
        return WorkflowSteps.action(
                id,
                variables -> {
                    throw new AssertionError(
                            "action factory must never be invoked during"
                                    + " static introspection");
                },
                output);
    }

    /** {@code count} distinct ASSIGN steps - one leaf-node-cost unit each, PARALLEL-branch-safe. */
    private static List<IWorkflowStep> assigns(String prefix, int count) {
        List<IWorkflowStep> steps = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            WorkflowVariable<Boolean> variable =
                    WorkflowVariable.publicValue(prefix + "-v" + i, Boolean.class);
            steps.add(WorkflowSteps.assign(prefix + "-" + i, variable, true));
        }
        return steps;
    }

    private static IWorkflowStep loop(String id, int maxIterations, List<IWorkflowStep> body) {
        return WorkflowSteps.loop(id, NEVER_EVALUATED, maxIterations, body);
    }

    private static IWorkflowStep ifThen(String id, List<IWorkflowStep> thenSteps) {
        return WorkflowSteps.ifThen(id, NEVER_EVALUATED, thenSteps);
    }

    private static IWorkflowStep ifElse(
            String id, List<IWorkflowStep> thenSteps, List<IWorkflowStep> elseSteps) {
        return WorkflowSteps.ifElse(id, NEVER_EVALUATED, thenSteps, elseSteps);
    }

    private static IWorkflowStep parallel(String id, List<List<IWorkflowStep>> branches) {
        return WorkflowSteps.parallel(id, branches);
    }

    // --- INTROSPECT-001: empty/minimal valid workflow ------------------------------------------

    @Test
    void introspect001MinimalValidWorkflow() {
        Workflow workflow = Workflow.builder("wf").step(neverRunAction("a")).build();

        WorkflowIntrospectionReport report = introspector.inspect(workflow);

        assertThat(report.workflowId()).isEqualTo(workflow.id());
        assertThat(report.definitionNodeCount()).isEqualTo(1);
        assertThat(report.maximumControlFlowDepth()).isZero();
        assertThat(report.conditionalCount()).isZero();
        assertThat(report.loopCount()).isZero();
        assertThat(report.parallelCount()).isZero();
        assertThat(report.actionCount()).isEqualTo(1);
        assertThat(report.maximumPotentialExecutionNodes()).isEqualTo(1);
        assertThat(report.maximumPotentialExecutionNodesSaturated()).isFalse();
        assertThat(report.mayExceedRuntimeNodeBudget()).isFalse();
        assertThat(report.containsActions()).isTrue();
        assertThat(report.containsLoops()).isFalse();
        assertThat(report.containsParallelism()).isFalse();
        assertThat(report.containsSecrets()).isFalse();
        assertThat(report.riskIndicators())
                .containsExactly(WorkflowStaticRiskIndicator.CONTAINS_ACTIONS);
    }

    // --- INTROSPECT-002: plain sequential steps ------------------------------------------------

    @Test
    void introspect002PlainSequentialSteps() {
        Workflow workflow =
                Workflow.builder("wf")
                        .step(neverRunAction("a"))
                        .step(neverRunAction("b"))
                        .step(neverRunAction("c"))
                        .build();

        WorkflowIntrospectionReport report = introspector.inspect(workflow);

        assertThat(report.definitionNodeCount()).isEqualTo(3);
        assertThat(report.actionCount()).isEqualTo(3);
        assertThat(report.maximumPotentialExecutionNodes()).isEqualTo(3);
        assertThat(report.maximumControlFlowDepth()).isZero();
    }

    // --- INTROSPECT-003: ifThen -----------------------------------------------------------------

    @Test
    void introspect003IfThen() {
        Workflow workflow =
                Workflow.builder("wf").step(ifThen("dec", List.of(neverRunAction("a")))).build();

        WorkflowIntrospectionReport report = introspector.inspect(workflow);

        assertThat(report.definitionNodeCount()).isEqualTo(2);
        assertThat(report.conditionalCount()).isEqualTo(1);
        assertThat(report.maximumControlFlowDepth()).isEqualTo(1);
        // 1 decision entry + the more expensive branch (THEN=1, implicit NONE=0).
        assertThat(report.maximumPotentialExecutionNodes()).isEqualTo(2);
    }

    // --- INTROSPECT-004: ifElse uses max branch, not sum, for runtime potential -----------------

    @Test
    void introspect004IfElseUsesMaxBranchNotSum() {
        Workflow workflow =
                Workflow.builder("wf")
                        .step(ifElse("dec", assigns("t", 100), assigns("e", 200)))
                        .build();

        WorkflowIntrospectionReport report = introspector.inspect(workflow);

        assertThat(report.definitionNodeCount()).isEqualTo(1 + 100 + 200);
        // 1 decision entry + max(100, 200) - never 100 + 200 + 1 = 301.
        assertThat(report.maximumPotentialExecutionNodes()).isEqualTo(201);
    }

    // --- INTROSPECT-005: single bounded loop -----------------------------------------------------

    @Test
    void introspect005SingleBoundedLoop() {
        Workflow workflow = Workflow.builder("wf").step(loop("lp", 5, assigns("b", 3))).build();

        WorkflowIntrospectionReport report = introspector.inspect(workflow);

        assertThat(report.definitionNodeCount()).isEqualTo(1 + 3);
        assertThat(report.loopCount()).isEqualTo(1);
        assertThat(report.maximumLoopIterations()).isEqualTo(5);
        // wrapper(1) + terminal decision(1) + 5 * (iteration decision(1) + body(3)) = 2 + 5*4 = 22.
        assertThat(report.maximumPotentialExecutionNodes()).isEqualTo(22);
    }

    // --- INTROSPECT-006: nested loops use multiplication without physical expansion -------------

    @Test
    void introspect006NestedLoopsMultiplyWithoutPhysicalExpansion() {
        IWorkflowStep innerLoop = loop("inner", 2, assigns("ib", 1));
        Workflow workflow =
                Workflow.builder("wf").step(loop("outer", 3, List.of(innerLoop))).build();

        WorkflowIntrospectionReport report = introspector.inspect(workflow);

        // Only 3 real declared steps regardless of maxIterations: outer, inner, and inner's body.
        assertThat(report.definitionNodeCount()).isEqualTo(3);
        assertThat(report.loopCount()).isEqualTo(2);
        assertThat(report.maximumControlFlowDepth()).isEqualTo(2);
        // inner: 2 + 2*(1+1) = 6. outer: 2 + 3*(1+6) = 2 + 21 = 23.
        assertThat(report.maximumPotentialExecutionNodes()).isEqualTo(23);
    }

    // --- INTROSPECT-007: parallel sums branch potential ------------------------------------------

    @Test
    void introspect007ParallelSumsBranchPotential() {
        Workflow workflow =
                Workflow.builder("wf")
                        .step(parallel("par", List.of(assigns("b0", 100), assigns("b1", 200))))
                        .build();

        WorkflowIntrospectionReport report = introspector.inspect(workflow);

        assertThat(report.parallelCount()).isEqualTo(1);
        assertThat(report.maximumParallelBranches()).isEqualTo(2);
        assertThat(report.totalParallelBranches()).isEqualTo(2);
        // wrapper(1) + [branch0 wrapper(1)+100] + [branch1 wrapper(1)+200] = 1+101+201 = 303 - the
        // sum of every branch, never max(100,200)+overhead.
        assertThat(report.maximumPotentialExecutionNodes()).isEqualTo(303);
    }

    // --- INTROSPECT-008: loop + parallel
    // ----------------------------------------------------------

    @Test
    void introspect008LoopWrappingParallel() {
        IWorkflowStep par = parallel("par", List.of(assigns("b0", 1), assigns("b1", 1)));
        Workflow workflow = Workflow.builder("wf").step(loop("lp", 2, List.of(par))).build();

        WorkflowIntrospectionReport report = introspector.inspect(workflow);

        assertThat(report.loopCount()).isEqualTo(1);
        assertThat(report.parallelCount()).isEqualTo(1);
        assertThat(report.maximumControlFlowDepth()).isEqualTo(2);
        // parallel: 1 + (1+1) + (1+1) = 5. loop: 2 + 2*(1+5) = 2+12 = 14.
        assertThat(report.maximumPotentialExecutionNodes()).isEqualTo(14);
    }

    // --- INTROSPECT-009: parallel + nested loop
    // ---------------------------------------------------

    @Test
    void introspect009ParallelWithNestedLoopBranch() {
        IWorkflowStep loopBranch = loop("lp", 3, assigns("lb", 2));
        Workflow workflow =
                Workflow.builder("wf")
                        .step(parallel("par", List.of(List.of(loopBranch), assigns("b1", 5))))
                        .build();

        WorkflowIntrospectionReport report = introspector.inspect(workflow);

        // loop: 2 + 3*(1+2) = 2+9 = 11.
        // parallel: 1 + (1+11) + (1+5) = 1+12+6 = 19.
        assertThat(report.maximumPotentialExecutionNodes()).isEqualTo(19);
    }

    // --- INTROSPECT-010: conditional + parallel + loop combined
    // -----------------------------------

    @Test
    void introspect010ConditionalParallelLoopCombined() {
        IWorkflowStep innerLoop = loop("lp", 4, assigns("lb", 2));
        IWorkflowStep par = parallel("par", List.of(List.of(innerLoop), assigns("b1", 3)));
        Workflow workflow = Workflow.builder("wf").step(ifThen("dec", List.of(par))).build();

        WorkflowIntrospectionReport report = introspector.inspect(workflow);

        assertThat(report.conditionalCount()).isEqualTo(1);
        assertThat(report.parallelCount()).isEqualTo(1);
        assertThat(report.loopCount()).isEqualTo(1);
        assertThat(report.maximumControlFlowDepth()).isEqualTo(3);
        // loop: 2 + 4*(1+2) = 14. parallel: 1 + (1+14) + (1+3) = 1+15+4 = 20. ifThen:
        // max(20,0)+1=21.
        assertThat(report.maximumPotentialExecutionNodes()).isEqualTo(21);
    }

    // --- INTROSPECT-011: maximum control-flow depth
    // ------------------------------------------------

    @Test
    void introspect011MaximumControlFlowDepth() {
        IWorkflowStep depth3 = loop("d3", 1, assigns("d3b", 1));
        IWorkflowStep depth2 = parallel("d2", List.of(List.of(depth3), assigns("d2b", 1)));
        IWorkflowStep depth1 = ifThen("d1", List.of(depth2));
        Workflow workflow = Workflow.builder("wf").step(depth1).build();

        WorkflowIntrospectionReport report = introspector.inspect(workflow);

        assertThat(report.maximumControlFlowDepth()).isEqualTo(3);
    }

    // --- INTROSPECT-012: maximum parallel branches
    // -------------------------------------------------

    @Test
    void introspect012MaximumParallelBranches() {
        List<List<IWorkflowStep>> eightBranches = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            eightBranches.add(assigns("big" + i, 1));
        }
        Workflow workflow =
                Workflow.builder("wf")
                        .step(parallel("bigPar", eightBranches))
                        .step(parallel("smallPar", List.of(assigns("s0", 1), assigns("s1", 1))))
                        .build();

        WorkflowIntrospectionReport report = introspector.inspect(workflow);

        assertThat(report.parallelCount()).isEqualTo(2);
        assertThat(report.maximumParallelBranches()).isEqualTo(8);
        assertThat(report.totalParallelBranches()).isEqualTo(10);
    }

    // --- INTROSPECT-013: secret outputs are metadata only
    // -------------------------------------------

    @Test
    void introspect013SecretOutputsAreMetadataOnly() {
        WorkflowVariable<String> secretOut = WorkflowVariable.secret("token");
        Workflow workflow = Workflow.builder("wf").step(neverRunAction("login", secretOut)).build();

        WorkflowIntrospectionReport report = introspector.inspect(workflow);

        assertThat(report.secretOutputCount()).isEqualTo(1);
        assertThat(report.containsSecrets()).isTrue();
        assertThat(report.riskIndicators())
                .contains(WorkflowStaticRiskIndicator.CONTAINS_SECRET_OUTPUTS);
        assertThat(report.outputs()).hasSize(1);
        WorkflowIntrospectionOutput output = report.outputs().get(0);
        assertThat(output.name()).isEqualTo("token");
        assertThat(output.secret()).isTrue();
        assertThat(output.typeName()).isEqualTo("String");
        // Never a value: the record has no value-carrying component to assert against, by design.
    }

    // --- INTROSPECT-014: definitely available outputs
    // -----------------------------------------------

    @Test
    void introspect014DefinitelyAvailableOutput() {
        WorkflowVariable<String> produced = WorkflowVariable.publicValue("produced", String.class);
        Workflow workflow = Workflow.builder("wf").step(neverRunAction("a", produced)).build();

        WorkflowIntrospectionReport report = introspector.inspect(workflow);

        assertThat(report.definitelyAvailableOutputCount()).isEqualTo(1);
        assertThat(report.outputs().get(0).definitelyAvailable()).isTrue();
    }

    // --- INTROSPECT-015: guarded output is not definite
    // ---------------------------------------------

    @Test
    void introspect015GuardedOutputIsNotDefinite() {
        WorkflowVariable<String> produced = WorkflowVariable.publicValue("produced", String.class);
        IWorkflowStep guarded = neverRunAction("a", produced).when(NEVER_EVALUATED);
        Workflow workflow = Workflow.builder("wf").step(guarded).build();

        WorkflowIntrospectionReport report = introspector.inspect(workflow);

        assertThat(report.declaredOutputCount()).isEqualTo(1);
        assertThat(report.definitelyAvailableOutputCount()).isZero();
        assertThat(report.outputs().get(0).definitelyAvailable()).isFalse();
    }

    // --- INTROSPECT-016: condition evaluate() (and describe()) never called
    // ------------------------

    @Test
    void introspect016ConditionNeverEvaluated() {
        AtomicInteger evaluations = new AtomicInteger();
        IWorkflowCondition counting = new CountingCondition(evaluations, true);
        Workflow workflow =
                Workflow.builder("wf")
                        .step(WorkflowSteps.ifThen("dec", counting, List.of(neverRunAction("a"))))
                        .step(WorkflowSteps.loop("lp", counting, 3, assigns("b", 1)))
                        .step(neverRunAction("guarded").when(counting))
                        .build();

        introspector.inspect(workflow);

        assertThat(evaluations.get()).isZero();
    }

    // --- INTROSPECT-017: action factory never called --------------------------------------------

    @Test
    void introspect017ActionFactoryNeverCalled() {
        AtomicInteger prepareCalls = new AtomicInteger();
        Workflow workflow =
                Workflow.builder("wf")
                        .step(countingAction("a", prepareCalls))
                        .step(ifThen("dec", List.of(countingAction("b", prepareCalls))))
                        .build();

        introspector.inspect(workflow);

        assertThat(prepareCalls.get()).isZero();
    }

    // --- INTROSPECT-018: zero backend/browser/network interaction --------------------------------

    @Test
    void introspect018ZeroBackendBrowserNetworkInteraction() {
        // Within webagent4j-workflow, the action pipeline (and therefore any backend, browser, or
        // network call it might make) is reachable exclusively through
        // IWorkflowActionFactory#prepare - never directly from a condition, a loop, or a parallel
        // step. Proving actionFactoryCalls stays at zero across every step type therefore proves
        // backendCalls/browserCalls/networkCalls are all zero too, transitively.
        AtomicInteger prepareCalls = new AtomicInteger();
        AtomicInteger evaluations = new AtomicInteger();
        IWorkflowCondition counting = new CountingCondition(evaluations, true);
        IWorkflowStep par =
                parallel(
                        "par",
                        List.of(assigns("pb0", 2), List.of(loop("plp", 2, assigns("plb", 1)))));
        Workflow workflow =
                Workflow.builder("wf")
                        .step(countingAction("a", prepareCalls))
                        .step(
                                WorkflowSteps.ifThen(
                                        "dec",
                                        counting,
                                        List.of(countingAction("b", prepareCalls))))
                        .step(loop("lp", 3, List.of(countingAction("c", prepareCalls))))
                        .step(par)
                        .build();

        introspector.inspect(workflow);

        assertThat(prepareCalls.get()).isZero();
        assertThat(evaluations.get()).isZero();
    }

    // --- INTROSPECT-019: repeated inspection is identical --------------------------------------

    @Test
    void introspect019RepeatedInspectionIsIdentical() {
        Workflow workflow =
                Workflow.builder("wf")
                        .step(ifElse("dec", assigns("t", 3), assigns("e", 4)))
                        .step(loop("lp", 5, assigns("b", 2)))
                        .build();

        WorkflowIntrospectionReport first = introspector.inspect(workflow);
        WorkflowIntrospectionReport second = introspector.inspect(workflow);

        assertThat(second).isEqualTo(first);

        Workflow independentlyBuilt =
                Workflow.builder("wf")
                        .step(ifElse("dec", assigns("t", 3), assigns("e", 4)))
                        .step(loop("lp", 5, assigns("b", 2)))
                        .build();
        assertThat(introspector.inspect(independentlyBuilt)).isEqualTo(first);
    }

    // --- INTROSPECT-020: returned collections are immutable --------------------------------------

    @Test
    void introspect020ReturnedCollectionsAreImmutable() {
        WorkflowVariable<String> secretOut = WorkflowVariable.secret("s");
        Workflow workflow =
                Workflow.builder("wf")
                        .requiredInput(WorkflowVariable.publicValue("in", String.class))
                        .step(neverRunAction("a", secretOut))
                        .build();

        WorkflowIntrospectionReport report = introspector.inspect(workflow);

        assertThatThrownBy(() -> report.inputs().add(null))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> report.outputs().add(null))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> report.riskIndicators().add(null))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    // --- INTROSPECT-021: null workflow fails fast
    // --------------------------------------------------

    @Test
    void introspect021NullWorkflowFailsFast() {
        assertThatThrownBy(() -> introspector.inspect(null))
                .isInstanceOf(NullPointerException.class);
    }

    // --- SAT-001/002/003: nested-loop multiplication overflows and saturates
    // -----------------------

    private static IWorkflowStep deeplyNestedLoops(String idPrefix, int levels, int maxIterations) {
        // ASSIGN, not ACTION: this helper is also used as a PARALLEL branch's own content (see
        // sat006), where an ACTION step is unconditionally forbidden.
        List<IWorkflowStep> body = assigns(idPrefix + "-leaf", 1);
        IWorkflowStep current = loop(idPrefix + "-l0", maxIterations, body);
        for (int level = 1; level < levels; level++) {
            current = loop(idPrefix + "-l" + level, maxIterations, List.of(current));
        }
        return current;
    }

    private static IWorkflowStep deeplyNestedLoops(int levels, int maxIterations) {
        return deeplyNestedLoops("n", levels, maxIterations);
    }

    @Test
    void sat001NestedLoopMultiplicationReachesVeryLargeValueWithoutOverflowException() {
        // 5 levels of maxIterations=10_000 loops: mathematically far beyond Long.MAX_VALUE
        // (10_000^5 = 10^20, versus a long's ~9.22 * 10^18 ceiling).
        Workflow workflow = Workflow.builder("wf").step(deeplyNestedLoops(5, 10_000)).build();

        assertTimeoutPreemptively(
                Duration.ofSeconds(5),
                () -> {
                    WorkflowIntrospectionReport report = introspector.inspect(workflow);
                    assertThat(report.maximumPotentialExecutionNodesSaturated()).isTrue();
                });
    }

    @Test
    void sat002OverflowSaturatesToLongMaxValue() {
        Workflow workflow = Workflow.builder("wf").step(deeplyNestedLoops(5, 10_000)).build();

        WorkflowIntrospectionReport report = introspector.inspect(workflow);

        assertThat(report.maximumPotentialExecutionNodes()).isEqualTo(Long.MAX_VALUE);
    }

    @Test
    void sat003SaturationFlagIsTrue() {
        Workflow workflow = Workflow.builder("wf").step(deeplyNestedLoops(5, 10_000)).build();

        WorkflowIntrospectionReport report = introspector.inspect(workflow);

        assertThat(report.maximumPotentialExecutionNodesSaturated()).isTrue();
        assertThat(report.riskIndicators())
                .contains(WorkflowStaticRiskIndicator.MAY_EXCEED_RUNTIME_NODE_BUDGET);
    }

    // --- SAT-004: a large but non-overflowing potential is not marked saturated
    // ---------------------

    @Test
    void sat004LargeButExactPotentialIsNotSaturated() {
        // A single loop of maxIterations=10_000 with a 90-step body: (90+1)*10_000+2 = 910_002 -
        // large, exactly computable in a long, and nowhere near overflow.
        Workflow workflow =
                Workflow.builder("wf").step(loop("lp", 10_000, assigns("b", 90))).build();

        WorkflowIntrospectionReport report = introspector.inspect(workflow);

        assertThat(report.maximumPotentialExecutionNodesSaturated()).isFalse();
        assertThat(report.maximumPotentialExecutionNodes()).isEqualTo(910_002L);
    }

    // --- SAT-005: saturated result is never negative -------------------------------------------

    @Test
    void sat005SaturatedResultIsNeverNegative() {
        Workflow workflow = Workflow.builder("wf").step(deeplyNestedLoops(6, 10_000)).build();

        WorkflowIntrospectionReport report = introspector.inspect(workflow);

        assertThat(report.maximumPotentialExecutionNodes()).isPositive();
    }

    // --- SAT-006: no silent wraparound - saturating addition path -----------------------------

    @Test
    void sat006SaturatingAdditionNeverWrapsAround() {
        // Two independent saturated (via nested-loop multiplication) branches summed by a PARALLEL
        // wrapper: a naive long addition of two already-overflowed values could otherwise wrap to a
        // small or negative number instead of staying saturated.
        IWorkflowStep hugeBranch0 = deeplyNestedLoops("a0", 5, 10_000);
        IWorkflowStep hugeBranch1 = deeplyNestedLoops("a1", 5, 10_000);
        Workflow workflow =
                Workflow.builder("wf")
                        .step(parallel("par", List.of(List.of(hugeBranch0), List.of(hugeBranch1))))
                        .build();

        WorkflowIntrospectionReport report = introspector.inspect(workflow);

        assertThat(report.maximumPotentialExecutionNodesSaturated()).isTrue();
        assertThat(report.maximumPotentialExecutionNodes()).isEqualTo(Long.MAX_VALUE);
    }

    // --- BUDGET-001..005: runtime budget comparison
    // -----------------------------------------------

    @Test
    void budget001PotentialBelowBudgetIsFalse() {
        Workflow workflow = Workflow.builder("wf").step(loop("lp", 3, assigns("b", 2))).build();

        WorkflowIntrospectionReport report = introspector.inspect(workflow);

        assertThat(report.maximumPotentialExecutionNodes()).isLessThan(100_000);
        assertThat(report.mayExceedRuntimeNodeBudget()).isFalse();
    }

    @Test
    void budget002PotentialExactlyAtBudgetIsFalse() {
        Workflow.Builder builder = Workflow.builder("wf");
        for (IWorkflowStep step : assigns("leaf", 100_000)) {
            builder.step(step);
        }
        Workflow workflow = builder.build();

        WorkflowIntrospectionReport report = introspector.inspect(workflow);

        assertThat(report.maximumPotentialExecutionNodes()).isEqualTo(100_000L);
        assertThat(report.mayExceedRuntimeNodeBudget()).isFalse();
    }

    @Test
    void budget003PotentialAboveBudgetIsTrue() {
        Workflow.Builder builder = Workflow.builder("wf");
        for (IWorkflowStep step : assigns("leaf", 100_001)) {
            builder.step(step);
        }
        Workflow workflow = builder.build();

        WorkflowIntrospectionReport report = introspector.inspect(workflow);

        assertThat(report.maximumPotentialExecutionNodes()).isEqualTo(100_001L);
        assertThat(report.mayExceedRuntimeNodeBudget()).isTrue();
        assertThat(report.riskIndicators())
                .contains(WorkflowStaticRiskIndicator.MAY_EXCEED_RUNTIME_NODE_BUDGET);
    }

    @Test
    void budget004SaturatedPotentialIsTrue() {
        Workflow workflow = Workflow.builder("wf").step(deeplyNestedLoops(5, 10_000)).build();

        WorkflowIntrospectionReport report = introspector.inspect(workflow);

        assertThat(report.maximumPotentialExecutionNodesSaturated()).isTrue();
        assertThat(report.mayExceedRuntimeNodeBudget()).isTrue();
    }

    @Test
    void budget005LargeStaticPotentialDoesNotInvalidateTheWorkflow() {
        // build() itself is the proof: a workflow whose declared bounds could exceed the runtime
        // budget still builds successfully - mayExceedRuntimeNodeBudget is information, not a
        // validation failure.
        Workflow workflow = Workflow.builder("wf").step(deeplyNestedLoops(5, 10_000)).build();

        assertThat(workflow).isNotNull();
        WorkflowIntrospectionReport report = introspector.inspect(workflow);
        assertThat(report.mayExceedRuntimeNodeBudget()).isTrue();
    }

    // --- INTROSPECT-GUARD-001..006: guard-aware definite assignment around CONDITIONAL/PARALLEL --

    /**
     * GUARD-001/002 establish, rather than assume, the exact scenario the guard-aware CONDITIONAL
     * merge in {@link WorkflowIntrospector#inspect(Workflow)} must reason about: unlike a leaf
     * ACTION/ASSIGN step or a {@code PARALLEL} step, an {@code ifElse}/{@code ifThen}-built step's
     * inherited condition slot is never an optional {@link IWorkflowStep#when} skip-guard - it is
     * the mandatory branch selector - and {@link ConditionalWorkflowStep#withCondition}/{@link
     * LoopWorkflowStep#withCondition} unconditionally throw {@link UnsupportedOperationException}.
     * A "guarded CONDITIONAL" (or "guarded LOOP") therefore cannot be constructed at all: {@code
     * step.condition().isPresent()} is always {@code true} for these two step types, holding their
     * mandatory selector/continuation-check, not an actual guard - which is exactly why {@link
     * WorkflowIntrospector#inspectStep} never consults it for either of them (see the comment at
     * the top of its {@code ConditionalWorkflowStep} branch).
     */
    @Test
    void introspectGuard001IfElseDoesNotSupportGuard() {
        IWorkflowStep ifElseStep =
                ifElse("dec", List.of(neverRunAction("t")), List.of(neverRunAction("e")));

        assertThatThrownBy(() -> ifElseStep.when(NEVER_EVALUATED))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void introspectGuard002IfThenDoesNotSupportGuard() {
        IWorkflowStep ifThenStep = ifThen("dec", List.of(neverRunAction("t")));

        assertThatThrownBy(() -> ifThenStep.when(NEVER_EVALUATED))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    // --- INTROSPECT-GUARD-003: unguarded ifElse, identical output in both branches, is definite --

    @Test
    void introspectGuard003UnguardedIfElseBothBranchesIdenticalOutputIsDefinite() {
        WorkflowVariable<String> shared = WorkflowVariable.publicValue("shared", String.class);
        Workflow workflow =
                Workflow.builder("wf")
                        .step(
                                ifElse(
                                        "dec",
                                        List.of(neverRunAction("t", shared)),
                                        List.of(neverRunAction("e", shared))))
                        .build();

        WorkflowIntrospectionReport report = introspector.inspect(workflow);

        assertThat(report.declaredOutputCount()).isEqualTo(1);
        assertThat(report.definitelyAvailableOutputCount()).isEqualTo(1);
        assertThat(report.outputs().get(0).definitelyAvailable()).isTrue();
    }

    // --- INTROSPECT-GUARD-004: a prior definite output survives a later conditional untouched ----

    @Test
    void introspectGuard004PriorDefiniteSurvivesAcrossConditional() {
        WorkflowVariable<String> earlier = WorkflowVariable.publicValue("earlier", String.class);
        Workflow workflow =
                Workflow.builder("wf")
                        .step(neverRunAction("a", earlier))
                        .step(ifThen("dec", List.of(neverRunAction("t"))))
                        .build();

        WorkflowIntrospectionReport report = introspector.inspect(workflow);

        assertThat(report.definitelyAvailableOutputCount()).isEqualTo(1);
        WorkflowIntrospectionOutput earlierOutput =
                report.outputs().stream()
                        .filter(output -> output.name().equals("earlier"))
                        .findFirst()
                        .orElseThrow();
        assertThat(earlierOutput.definitelyAvailable()).isTrue();
    }

    // --- INTROSPECT-GUARD-005: an unguarded ifElse nested in a guarded PARALLEL is not promoted --

    @Test
    void introspectGuard005GuardedParallelSuppressesNestedConditionalDefinite() {
        WorkflowVariable<String> shared = WorkflowVariable.publicValue("shared", String.class);
        IWorkflowStep nestedIfElse =
                ifElse(
                        "dec",
                        List.of(WorkflowSteps.assign("t", shared, "x")),
                        List.of(WorkflowSteps.assign("e", shared, "x")));
        IWorkflowStep guardedParallel =
                parallel("par", List.of(List.of(nestedIfElse), assigns("b1", 1)))
                        .when(NEVER_EVALUATED);
        Workflow workflow = Workflow.builder("wf").step(guardedParallel).build();

        WorkflowIntrospectionReport report = introspector.inspect(workflow);

        WorkflowIntrospectionOutput sharedOutput =
                report.outputs().stream()
                        .filter(output -> output.name().equals("shared"))
                        .findFirst()
                        .orElseThrow();
        assertThat(sharedOutput.definitelyAvailable()).isFalse();
        assertThat(report.definitelyAvailableOutputCount()).isZero();
    }

    // --- INTROSPECT-GUARD-006: a guarded PARALLEL's own leaf output is never definite ------------

    @Test
    void introspectGuard006GuardedParallelLeafOutputIsNotDefinite() {
        WorkflowVariable<String> produced = WorkflowVariable.publicValue("produced", String.class);
        IWorkflowStep guardedParallel =
                parallel(
                                "par",
                                List.of(
                                        List.of(WorkflowSteps.assign("b0", produced, "x")),
                                        assigns("b1", 1)))
                        .when(NEVER_EVALUATED);
        Workflow workflow = Workflow.builder("wf").step(guardedParallel).build();

        WorkflowIntrospectionReport report = introspector.inspect(workflow);

        assertThat(report.declaredOutputCount()).isEqualTo(2);
        assertThat(report.definitelyAvailableOutputCount()).isZero();
        WorkflowIntrospectionOutput producedOutput =
                report.outputs().stream()
                        .filter(output -> output.name().equals("produced"))
                        .findFirst()
                        .orElseThrow();
        assertThat(producedOutput.definitelyAvailable()).isFalse();
    }

    // --- INTROSPECT-COST-001..005: linear-cost delta traversal, not cumulative full-state copies
    // --

    @Test
    void introspectCost001LargeSequentialDefinitionCompletesQuickly() {
        Workflow.Builder builder = Workflow.builder("wf");
        for (IWorkflowStep step : assigns("leaf", 50_000)) {
            builder.step(step);
        }
        Workflow workflow = builder.build();

        assertTimeoutPreemptively(
                Duration.ofSeconds(10),
                () -> {
                    WorkflowIntrospectionReport report = introspector.inspect(workflow);
                    assertThat(report.declaredOutputCount()).isEqualTo(50_000);
                    assertThat(report.definitelyAvailableOutputCount()).isEqualTo(50_000);
                });
    }

    /**
     * Many sequential (never nested) {@code ifElse} steps, each declaring its own new pair of
     * identically-named then/else outputs: by the time the traversal reaches the last one, the
     * running {@code declared}/{@code definite} sets already hold thousands of entries. The
     * pre-fix, full-state-copy {@code inspectBranch} would copy that entire growing history into
     * both branches of every single one of these conditionals - quadratic in the count below; the
     * delta-based traversal never does, so this stays fast regardless of count.
     */
    @Test
    void introspectCost002ManyConditionalsWithGrowingOutputHistoryCompletesQuickly() {
        Workflow.Builder builder = Workflow.builder("wf");
        int count = 5_000;
        for (int i = 0; i < count; i++) {
            WorkflowVariable<String> shared =
                    WorkflowVariable.publicValue("cond-shared-" + i, String.class);
            builder.step(
                    ifElse(
                            "dec-" + i,
                            List.of(neverRunAction("t-" + i, shared)),
                            List.of(neverRunAction("e-" + i, shared))));
        }
        Workflow workflow = builder.build();

        assertTimeoutPreemptively(
                Duration.ofSeconds(10),
                () -> {
                    WorkflowIntrospectionReport report = introspector.inspect(workflow);
                    assertThat(report.declaredOutputCount()).isEqualTo(count);
                    assertThat(report.definitelyAvailableOutputCount()).isEqualTo(count);
                });
    }

    /**
     * Same growing-history hazard as COST-002, for sequential (never nested) {@code PARALLEL}
     * containers instead of conditionals.
     */
    @Test
    void introspectCost003ManyParallelContainersWithGrowingOutputHistoryCompletesQuickly() {
        Workflow.Builder builder = Workflow.builder("wf");
        int count = 2_000;
        for (int i = 0; i < count; i++) {
            builder.step(
                    parallel(
                            "par-" + i,
                            List.of(
                                    assigns("par-" + i + "-b0", 1),
                                    assigns("par-" + i + "-b1", 1))));
        }
        Workflow workflow = builder.build();

        assertTimeoutPreemptively(
                Duration.ofSeconds(10),
                () -> {
                    WorkflowIntrospectionReport report = introspector.inspect(workflow);
                    assertThat(report.declaredOutputCount()).isEqualTo(count * 2);
                    assertThat(report.definitelyAvailableOutputCount()).isEqualTo(count * 2);
                });
    }

    /** Deep (but within {@code Workflow}'s own bound), rather than wide, nested control flow. */
    @Test
    void introspectCost004DeepNestedBoundedControlFlowCompletesQuickly() {
        // MAX_CONDITIONAL_NESTING_DEPTH / MAX_CONTROL_FLOW_NESTING_DEPTH is 64: alternate
        // ifThen/loop/parallel nesting up to that bound, one level at a time.
        IWorkflowStep current = assigns("leaf", 1).get(0);
        for (int level = 0; level < 60; level++) {
            IWorkflowStep next =
                    switch (level % 3) {
                        case 0 -> ifThen("d" + level, List.of(current));
                        case 1 -> loop("l" + level, 3, List.of(current));
                        default ->
                                parallel(
                                        "p" + level,
                                        List.of(List.of(current), assigns("b" + level, 1)));
                    };
            current = next;
        }
        Workflow workflow = Workflow.builder("wf").step(current).build();

        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> introspector.inspect(workflow));
    }

    /**
     * Output order stays first-appearance-in-declaration order - never hash-based - even once the
     * traversal has accumulated a large number of entries across many sequential branches.
     */
    @Test
    void introspectCost005OutputOrderRemainsDeterministic() {
        Workflow.Builder builder = Workflow.builder("wf");
        List<String> expectedOrder = new ArrayList<>();
        int count = 500;
        for (int i = 0; i < count; i++) {
            String name = "seq-" + i;
            builder.step(
                    neverRunAction("a-" + i, WorkflowVariable.publicValue(name, String.class)));
            expectedOrder.add(name);
        }
        Workflow workflow = builder.build();

        WorkflowIntrospectionReport first = introspector.inspect(workflow);
        WorkflowIntrospectionReport second = introspector.inspect(workflow);

        List<String> actualOrder =
                first.outputs().stream().map(WorkflowIntrospectionOutput::name).toList();
        assertThat(actualOrder).containsExactlyElementsOf(expectedOrder);
        assertThat(second.outputs().stream().map(WorkflowIntrospectionOutput::name).toList())
                .containsExactlyElementsOf(expectedOrder);
    }

    // --- INTROSPECT-DATAFLOW-001: WorkflowIntrospector agrees with Workflow.Builder
    // ---------------

    /**
     * A condition whose {@code referencedVariables()} answers normally (so {@code
     * Workflow.Builder#build()}'s metadata-driven {@code OUTPUT_NOT_DEFINITELY_AVAILABLE} check can
     * actually run against it) but whose {@code evaluate()}/{@code describe()} still throw, since
     * neither {@code build()} nor {@link WorkflowIntrospector#inspect(Workflow)} ever needs to call
     * either.
     */
    private static IWorkflowCondition requiring(WorkflowVariable<?> variable) {
        return new IWorkflowCondition() {
            @Override
            public boolean evaluate(IWorkflowVariables variables) {
                throw new AssertionError("must not evaluate during build()/introspection");
            }

            @Override
            public String describe() {
                throw new AssertionError("must not describe during build()/introspection");
            }

            @Override
            public Set<WorkflowVariable<?>> referencedVariables() {
                return Set.of(variable);
            }
        };
    }

    /**
     * Builds {@code producerStep} alone, reads {@link WorkflowIntrospector}'s own {@code
     * definitelyAvailable} conclusion for {@code produced} from it, then builds a second workflow
     * appending a trailing, guarded no-op {@code ASSIGN} step whose guard requires {@code produced}
     * - the same {@code OUTPUT_NOT_DEFINITELY_AVAILABLE} check {@code Workflow.Builder} already
     * applies to any condition - and asserts {@code build()} accepts that second workflow if and
     * only if the introspector already called {@code produced} definitely available.
     */
    private void assertBuilderAgreesWithIntrospector(
            String label, WorkflowVariable<String> produced, IWorkflowStep producerStep) {
        Workflow producerOnly = Workflow.builder("wf-" + label).step(producerStep).build();
        WorkflowIntrospectionReport report = introspector.inspect(producerOnly);
        boolean introspectorSaysDefinite =
                report.outputs().stream()
                        .filter(output -> output.name().equals(produced.name()))
                        .findFirst()
                        .orElseThrow()
                        .definitelyAvailable();

        WorkflowVariable<Boolean> consumerOutput =
                WorkflowVariable.publicValue(label + "-consumer-out", Boolean.class);
        Workflow.Builder consumerBuilder =
                Workflow.builder("wf-" + label + "-consumer")
                        .step(producerStep)
                        .step(
                                WorkflowSteps.assign("consumer-" + label, consumerOutput, true)
                                        .when(requiring(produced)));
        boolean builderAccepts;
        try {
            consumerBuilder.build();
            builderAccepts = true;
        } catch (IllegalArgumentException rejectedAsNotDefinitelyAvailable) {
            builderAccepts = false;
        }

        assertThat(introspectorSaysDefinite)
                .as(
                        "shape '%s': WorkflowIntrospector.definitelyAvailable=%s vs"
                                + " Workflow.Builder accepted=%s",
                        label, introspectorSaysDefinite, builderAccepts)
                .isEqualTo(builderAccepts);
    }

    /**
     * Cross-checks {@link WorkflowIntrospector}'s independently-recomputed {@code
     * definitelyAvailable} conclusion against {@code Workflow.Builder}'s own accept/reject behavior
     * across every shape the guard-aware rule must handle. A "guarded ifElse"/"guarded ifThen"
     * shape is intentionally not included: as GUARD-001/002 establish, {@code ifElse}/{@code
     * ifThen} never support an optional guard at all ({@code when(...)} unconditionally throws
     * {@link UnsupportedOperationException}), so that shape cannot be constructed to cross-check in
     * the first place.
     */
    @Test
    void introspectDataflow001BuilderAgreesWithIntrospectorAcrossShapes() {
        assertBuilderAgreesWithIntrospector(
                "leaf",
                WorkflowVariable.publicValue("leaf-out", String.class),
                neverRunAction("producer", WorkflowVariable.publicValue("leaf-out", String.class)));

        WorkflowVariable<String> guardedLeafOut =
                WorkflowVariable.publicValue("guarded-leaf-out", String.class);
        assertBuilderAgreesWithIntrospector(
                "guarded-leaf",
                guardedLeafOut,
                neverRunAction("producer", guardedLeafOut).when(NEVER_EVALUATED));

        WorkflowVariable<String> ifThenOut =
                WorkflowVariable.publicValue("ifthen-out", String.class);
        assertBuilderAgreesWithIntrospector(
                "ifthen", ifThenOut, ifThen("dec", List.of(neverRunAction("t", ifThenOut))));

        WorkflowVariable<String> ifElseOut =
                WorkflowVariable.publicValue("ifelse-out", String.class);
        assertBuilderAgreesWithIntrospector(
                "ifelse",
                ifElseOut,
                ifElse(
                        "dec",
                        List.of(neverRunAction("t", ifElseOut)),
                        List.of(neverRunAction("e", ifElseOut))));

        WorkflowVariable<String> loopOut = WorkflowVariable.publicValue("loop-out", String.class);
        assertBuilderAgreesWithIntrospector(
                "loop", loopOut, loop("lp", 3, List.of(neverRunAction("body", loopOut))));

        WorkflowVariable<String> parallelOut =
                WorkflowVariable.publicValue("parallel-out", String.class);
        assertBuilderAgreesWithIntrospector(
                "parallel",
                parallelOut,
                parallel(
                        "par",
                        List.of(
                                List.of(WorkflowSteps.assign("b0", parallelOut, "x")),
                                assigns("b1", 1))));

        WorkflowVariable<String> guardedParallelOut =
                WorkflowVariable.publicValue("guarded-parallel-out", String.class);
        assertBuilderAgreesWithIntrospector(
                "guarded-parallel",
                guardedParallelOut,
                parallel(
                                "par",
                                List.of(
                                        List.of(
                                                WorkflowSteps.assign(
                                                        "b0", guardedParallelOut, "x")),
                                        assigns("b1", 1)))
                        .when(NEVER_EVALUATED));
    }
}
