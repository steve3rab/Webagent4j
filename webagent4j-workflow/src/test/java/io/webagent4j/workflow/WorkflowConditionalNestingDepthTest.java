package io.webagent4j.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Bounds conditional nesting depth: {@link Workflow.Builder#build()} rejects a definition that
 * would force {@code validateStep}/{@code validateBranch} (build-time) or {@code runSteps}/{@code
 * executeConditionalStepInto} (execution-time) into unbounded recursion, closing the {@link
 * StackOverflowError} gap a sufficiently deep - hostile or accidental - {@code ifElse}/{@code
 * ifThen} chain would otherwise open. See {@code Workflow#MAX_CONDITIONAL_NESTING_DEPTH}'s Javadoc
 * for the exact depth semantics this matrix proves: a top-level conditional is depth 1, one nested
 * inside either of its branches is depth 2, a non-conditional step never contributes to depth, and
 * a conditional's two branches are measured independently rather than summed.
 *
 * <p>Every deep structure below is built with a plain iterative loop, never recursive test-helper
 * calls, so constructing the fixture itself can never risk a {@link StackOverflowError} before the
 * validation under test even runs.
 */
class WorkflowConditionalNestingDepthTest {

    private static final int MAX = Workflow.MAX_CONDITIONAL_NESTING_DEPTH;
    private static final WorkflowVariable<String> LEAF_OUTPUT =
            WorkflowVariable.publicValue("leafOutput", String.class);

    /** A condition that counts every {@code evaluate()} call and returns a fixed outcome. */
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

    private static IWorkflowStep countingLeafStep(String id, AtomicInteger executions) {
        return WorkflowSteps.action(
                id,
                variables -> new FakePreparedAction<>(ActionResults.success("v"), executions),
                LEAF_OUTPUT);
    }

    /** Same as {@link #countingLeafStep}, but declares no output - safe to repeat many times. */
    private static IWorkflowStep noOutputLeafStep(String id, AtomicInteger executions) {
        return WorkflowSteps.action(
                id, variables -> new FakePreparedAction<>(ActionResults.success("v"), executions));
    }

    /**
     * Iteratively builds a chain of {@code depth} nested {@code ifThen} steps wrapping {@code leaf}
     * at the bottom, and returns the single outermost step - depth 0 returns {@code leaf} itself
     * unwrapped. Built bottom-up with a plain loop (never recursion), so this helper's own stack
     * usage is O(1) regardless of {@code depth}.
     */
    private static IWorkflowStep nestedChain(
            String idPrefix, int depth, AtomicInteger evaluations, IWorkflowStep leaf) {
        IWorkflowStep current = leaf;
        for (int level = depth; level >= 1; level--) {
            current =
                    WorkflowSteps.ifThen(
                            idPrefix + "-" + level,
                            new CountingCondition(true, evaluations),
                            List.of(current));
        }
        return current;
    }

    // --- DEPTH-001: exactly MAX_CONDITIONAL_NESTING_DEPTH is accepted -----------------------

    @Test
    void depth001ExactlyMaxDepthIsAcceptedAndExecutesCleanly() {
        AtomicInteger evaluations = new AtomicInteger();
        AtomicInteger leafExecutions = new AtomicInteger();
        IWorkflowStep root =
                nestedChain("d", MAX, evaluations, countingLeafStep("leaf", leafExecutions));

        Workflow workflow = Workflow.builder("wf-depth-max").step(root).build();

        assertThat(workflow).isNotNull();

        // Also prove the runtime side of the invariant at the accepted limit: no
        // StackOverflowError, one evaluation per reached conditional, the leaf runs exactly once.
        WorkflowResult result = new WorkflowEngine().execute(workflow, WorkflowInputs.empty());

        assertThat(result.completed()).isTrue();
        assertThat(evaluations).hasValue(MAX);
        assertThat(leafExecutions).hasValue(1);
        assertThat(result.steps()).hasSize(MAX + 1);
    }

    // --- DEPTH-002: MAX_CONDITIONAL_NESTING_DEPTH + 1 is rejected, never StackOverflowError -

    @Test
    void depth002OneMoreThanMaxIsRejectedWithAControlledException() {
        AtomicInteger evaluations = new AtomicInteger();
        IWorkflowStep root =
                nestedChain(
                        "d", MAX + 1, evaluations, countingLeafStep("leaf", new AtomicInteger()));
        Workflow.Builder builder = Workflow.builder("wf-depth-max-plus-one").step(root);

        assertThatThrownBy(builder::build)
                .isInstanceOf(IllegalArgumentException.class)
                .isNotInstanceOf(StackOverflowError.class)
                .hasMessageContaining(String.valueOf(MAX));
        // No side effect and no evaluation may have happened - a build-time rejection stops
        // before any execution exists at all.
        assertThat(evaluations).hasValue(0);
    }

    // --- DEPTH-003: independent branches - not cumulative -------------------------------------

    @Test
    void depth003EachBranchIndependentlyAtMaxDepthIsAccepted() {
        AtomicInteger evaluations = new AtomicInteger();
        // The root conditional is depth 1; each branch may nest MAX - 1 further levels to reach
        // depth MAX itself, independently - never summed between THEN and ELSE.
        IWorkflowStep thenChain =
                nestedChain(
                        "then",
                        MAX - 1,
                        evaluations,
                        countingLeafStep("then-leaf", new AtomicInteger()));
        IWorkflowStep elseChain =
                nestedChain(
                        "else",
                        MAX - 1,
                        evaluations,
                        countingLeafStep("else-leaf", new AtomicInteger()));
        IWorkflowStep root =
                WorkflowSteps.ifElse(
                        "root",
                        new CountingCondition(true, evaluations),
                        List.of(thenChain),
                        List.of(elseChain));

        Workflow workflow = Workflow.builder("wf-depth-independent-branches").step(root).build();

        assertThat(workflow).isNotNull();
    }

    @Test
    void depth003OneBranchExceedingMaxIsRejectedEvenIfTheOtherIsShallow() {
        AtomicInteger evaluations = new AtomicInteger();
        IWorkflowStep deepThen =
                nestedChain(
                        "then",
                        MAX, // depth 1 (root) + MAX inside THEN = MAX + 1 overall for THEN's chain
                        evaluations,
                        countingLeafStep("then-leaf", new AtomicInteger()));
        IWorkflowStep shallowElse = countingLeafStep("else-leaf", new AtomicInteger());
        IWorkflowStep root =
                WorkflowSteps.ifElse(
                        "root",
                        new CountingCondition(true, evaluations),
                        List.of(deepThen),
                        List.of(shallowElse));
        Workflow.Builder builder = Workflow.builder("wf-depth-then-too-deep").step(root);

        assertThatThrownBy(builder::build).isInstanceOf(IllegalArgumentException.class);
    }

    // --- DEPTH-004: non-conditional steps never increase depth ------------------------------

    @Test
    void depth004NonConditionalStepsBetweenConditionalsDoNotCountTowardDepth() {
        AtomicInteger evaluations = new AtomicInteger();
        // MAX sibling top-level conditionals (each its own depth-1 chain of depth 1, i.e. not
        // nested in each other), interleaved with plain assign steps, must build cleanly: depth
        // is about nesting, never about how many steps or conditionals a workflow contains.
        Workflow.Builder builder = Workflow.builder("wf-depth-siblings-not-cumulative");
        for (int i = 0; i < MAX; i++) {
            WorkflowVariable<String> assignedVar =
                    WorkflowVariable.publicValue("assigned-" + i, String.class);
            builder.step(WorkflowSteps.assign("assign-" + i, assignedVar, "v" + i));
            builder.step(
                    WorkflowSteps.ifThen(
                            "cond-" + i,
                            new CountingCondition(true, evaluations),
                            List.of(noOutputLeafStep("leaf-" + i, new AtomicInteger()))));
        }

        Workflow workflow = builder.build();

        assertThat(workflow).isNotNull();

        // And one single conditional nested MAX levels deep, with a plain assign step immediately
        // before it at the top level, must also build cleanly: the assign step contributes
        // nothing to the conditional depth that follows it.
        AtomicInteger deepEvaluations = new AtomicInteger();
        IWorkflowStep deepChain =
                nestedChain(
                        "deep",
                        MAX,
                        deepEvaluations,
                        noOutputLeafStep("deep-leaf", new AtomicInteger()));
        Workflow deepWorkflow =
                Workflow.builder("wf-depth-assign-then-deep-chain")
                        .step(WorkflowSteps.assign("seed", LEAF_OUTPUT, "seed-value"))
                        .step(deepChain)
                        .build();

        assertThat(deepWorkflow).isNotNull();
    }

    // --- DEPTH-005: runtime invariant preserved at depth --------------------------------------

    @Test
    void depth005ExactlyOneEvaluationPerReachedConditionalAtDepthAndNoneBeyondAFalseTurn() {
        // A chain of true decisions down to depth reachableDepth, whose innermost conditional
        // ("turn") decides false: its THEN (a further chain reaching all the way to MAX) must
        // never be evaluated or entered at all - only its ELSE (a single leaf) runs.
        AtomicInteger evaluations = new AtomicInteger();
        int reachableDepth = MAX / 2;
        int neverTakenDepth = MAX - reachableDepth;

        IWorkflowStep neverTaken =
                nestedChain(
                        "unreachable",
                        neverTakenDepth,
                        evaluations,
                        countingLeafStep("unreachable-leaf", new AtomicInteger()));
        AtomicInteger stopExecutions = new AtomicInteger();
        IWorkflowStep turningPoint =
                WorkflowSteps.ifElse(
                        "turn",
                        new CountingCondition(false, evaluations),
                        List.of(neverTaken),
                        List.of(countingLeafStep("stop", stopExecutions)));
        IWorkflowStep root = nestedChain("true", reachableDepth - 1, evaluations, turningPoint);

        Workflow workflow = Workflow.builder("wf-depth-false-partway").step(root).build();
        WorkflowResult result = new WorkflowEngine().execute(workflow, WorkflowInputs.empty());

        assertThat(result.completed()).isTrue();
        // (reachableDepth - 1) true wrappers, each evaluated once, plus the one false "turn"
        // evaluation - never the neverTakenDepth conditionals past it.
        assertThat(evaluations).hasValue(reachableDepth);
        assertThat(stopExecutions).hasValue(1);
    }
}
