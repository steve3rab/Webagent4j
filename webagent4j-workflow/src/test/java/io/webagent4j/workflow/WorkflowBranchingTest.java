package io.webagent4j.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import io.webagent4j.action.ActionFailureType;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Deterministic Workflow Branching (if/else) invariant matrix: evaluate-once, exactly-one-branch,
 * zero side effects from the non-selected branch, fail-closed condition failures, interruption
 * boundaries, no hidden re-evaluation, nested branching, and mutation-after-decision safety. See
 * {@code docs/workflow.md#branching}.
 */
class WorkflowBranchingTest {

    private static final String SECRET_SENTINEL = "WA4J_BRANCH_SECRET_551209";
    private static final WorkflowVariable<String> PASSWORD = WorkflowVariable.secret("password");

    private final WorkflowEngine engine = new WorkflowEngine();

    @AfterEach
    void clearInterruptFlag() {
        // Several tests below deliberately interrupt the test thread to prove a boundary check;
        // always leave the thread's own interrupt status clean for whatever runs next.
        Thread.interrupted();
    }

    private static IWorkflowStep countingStep(String id, AtomicInteger counter) {
        return WorkflowSteps.action(
                id, variables -> new FakePreparedAction<>(ActionResults.success("ok"), counter));
    }

    private static IWorkflowStep failingStep(String id, AtomicInteger counter) {
        return WorkflowSteps.action(
                id,
                variables ->
                        new FakePreparedAction<>(
                                ActionResults.failure(
                                        ActionFailureType.TARGET_NOT_FOUND, "not found"),
                                counter));
    }

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

    // --- BRANCH-001: true decision runs THEN only -------------------------------------------

    @Test
    void branch001TrueDecisionRunsThenOnly() {
        AtomicInteger evaluations = new AtomicInteger();
        AtomicInteger thenCount = new AtomicInteger();
        AtomicInteger elseCount = new AtomicInteger();
        Workflow workflow =
                Workflow.builder("wf")
                        .step(
                                WorkflowSteps.ifElse(
                                        "branch",
                                        new CountingCondition(true, evaluations),
                                        List.of(countingStep("then", thenCount)),
                                        List.of(countingStep("else", elseCount))))
                        .build();

        WorkflowResult result = engine.execute(workflow, WorkflowInputs.empty());

        assertThat(result.completed()).isTrue();
        assertThat(evaluations).hasValue(1);
        assertThat(thenCount).hasValue(1);
        assertThat(elseCount).hasValue(0);
    }

    // --- BRANCH-002: false decision runs ELSE only ------------------------------------------

    @Test
    void branch002FalseDecisionRunsElseOnly() {
        AtomicInteger evaluations = new AtomicInteger();
        AtomicInteger thenCount = new AtomicInteger();
        AtomicInteger elseCount = new AtomicInteger();
        Workflow workflow =
                Workflow.builder("wf")
                        .step(
                                WorkflowSteps.ifElse(
                                        "branch",
                                        new CountingCondition(false, evaluations),
                                        List.of(countingStep("then", thenCount)),
                                        List.of(countingStep("else", elseCount))))
                        .build();

        WorkflowResult result = engine.execute(workflow, WorkflowInputs.empty());

        assertThat(result.completed()).isTrue();
        assertThat(evaluations).hasValue(1);
        assertThat(thenCount).hasValue(0);
        assertThat(elseCount).hasValue(1);
    }

    // --- BRANCH-003: condition failure fails closed -----------------------------------------

    @Test
    void branch003ConditionFailureFailsClosedNeitherBranchRuns() {
        AtomicInteger thenCount = new AtomicInteger();
        AtomicInteger elseCount = new AtomicInteger();
        IWorkflowCondition throwing =
                new IWorkflowCondition() {
                    @Override
                    public boolean evaluate(IWorkflowVariables variables) {
                        throw new RuntimeException("boom");
                    }

                    @Override
                    public String describe() {
                        return "throwing";
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
                                        throwing,
                                        List.of(countingStep("then", thenCount)),
                                        List.of(countingStep("else", elseCount))))
                        .build();

        WorkflowResult result = engine.execute(workflow, WorkflowInputs.empty());

        assertThat(result.completed()).isFalse();
        assertThat(result.failure().orElseThrow().type())
                .isEqualTo(WorkflowFailureType.CONDITION_EVALUATION_FAILED);
        assertThat(thenCount).hasValue(0);
        assertThat(elseCount).hasValue(0);
        assertThat(result.steps()).hasSize(1);
        assertThat(result.steps().get(0).status()).isEqualTo(WorkflowStepStatus.FAILED);
    }

    // --- BRANCH-004: pre-interrupted before the conditional is ever reached ----------------

    @Test
    void branch004PreInterruptedBeforeConditionalNeverEvaluatesOrRuns() {
        AtomicInteger evaluations = new AtomicInteger();
        AtomicInteger thenCount = new AtomicInteger();
        AtomicInteger elseCount = new AtomicInteger();
        Workflow workflow =
                Workflow.builder("wf")
                        .step(
                                WorkflowSteps.ifElse(
                                        "branch",
                                        new CountingCondition(true, evaluations),
                                        List.of(countingStep("then", thenCount)),
                                        List.of(countingStep("else", elseCount))))
                        .build();

        Thread.currentThread().interrupt();
        WorkflowResult result = engine.execute(workflow, WorkflowInputs.empty());

        assertThat(Thread.currentThread().isInterrupted())
                .as("the interrupt flag must be preserved, never silently cleared")
                .isTrue();
        assertThat(result.completed()).isFalse();
        assertThat(result.failure().orElseThrow().type())
                .isEqualTo(WorkflowFailureType.CONDITIONAL_STEP_INTERRUPTED);
        assertThat(evaluations).hasValue(0);
        assertThat(thenCount).hasValue(0);
        assertThat(elseCount).hasValue(0);
    }

    // --- BRANCH-005: interrupted before the conditional is reached, via an earlier step ----

    @Test
    void branch005InterruptedBetweenAnEarlierStepAndTheConditionalNeverEvaluates() {
        // WorkflowEngine has no workflow-wide timeout of its own (see its class Javadoc) - every
        // deadline in this codebase is enforced by the action/browser backend layer. The
        // conditional step's own "before condition" boundary is therefore observed as the
        // executing thread's interrupt flag, exactly like the action pipeline's own boundary
        // checks - simulated here as if an earlier step's own (real) action budget had just
        // expired and propagated an interrupt, deterministically, with no Thread.sleep.
        AtomicInteger evaluations = new AtomicInteger();
        AtomicInteger thenCount = new AtomicInteger();
        AtomicInteger elseCount = new AtomicInteger();
        IWorkflowStep interruptingStep =
                WorkflowSteps.action(
                        "earlier",
                        variables -> {
                            Thread.currentThread().interrupt();
                            return new FakePreparedAction<>(
                                    ActionResults.success("ok"), new AtomicInteger());
                        });
        Workflow workflow =
                Workflow.builder("wf")
                        .step(interruptingStep)
                        .step(
                                WorkflowSteps.ifElse(
                                        "branch",
                                        new CountingCondition(true, evaluations),
                                        List.of(countingStep("then", thenCount)),
                                        List.of(countingStep("else", elseCount))))
                        .build();

        WorkflowResult result = engine.execute(workflow, WorkflowInputs.empty());

        assertThat(result.completed()).isFalse();
        assertThat(result.failure().orElseThrow().type())
                .isEqualTo(WorkflowFailureType.CONDITIONAL_STEP_INTERRUPTED);
        assertThat(evaluations).hasValue(0);
        assertThat(thenCount).hasValue(0);
        assertThat(elseCount).hasValue(0);
    }

    // --- BRANCH-006: interrupted after the decision, before the branch starts --------------

    @Test
    void branch006InterruptedAfterDecisionBeforeBranchStartsBlocksTheBranch() {
        AtomicInteger evaluations = new AtomicInteger();
        AtomicInteger thenCount = new AtomicInteger();
        AtomicInteger elseCount = new AtomicInteger();
        // A deterministic seam, not a sleep: the condition itself raises the interrupt as its
        // last action before returning, so the engine's second boundary check - immediately
        // after the decision is captured, immediately before the selected branch starts -
        // deterministically observes it.
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
                                        List.of(countingStep("then", thenCount)),
                                        List.of(countingStep("else", elseCount))))
                        .build();

        WorkflowResult result = engine.execute(workflow, WorkflowInputs.empty());

        assertThat(result.completed()).isFalse();
        assertThat(result.failure().orElseThrow().type())
                .isEqualTo(WorkflowFailureType.CONDITIONAL_STEP_INTERRUPTED);
        assertThat(evaluations).hasValue(1);
        assertThat(thenCount).hasValue(0);
        assertThat(elseCount).hasValue(0);
        // The decision itself was still captured before the interruption was observed.
        WorkflowStepResult branchResult = result.steps().get(0);
        assertThat(branchResult.condition()).isPresent();
        assertThat(branchResult.condition().orElseThrow().outcome()).isTrue();
    }

    // --- BRANCH-007: the non-selected branch never invokes its backend ---------------------

    @Test
    void branch007NonSelectedBranchNeverInvokesItsBackend() {
        AtomicInteger evaluations = new AtomicInteger();
        AtomicInteger thenBackendInvocations = new AtomicInteger();
        AtomicInteger elseBackendInvocations = new AtomicInteger();
        Workflow workflow =
                Workflow.builder("wf")
                        .step(
                                WorkflowSteps.ifElse(
                                        "branch",
                                        new CountingCondition(true, evaluations),
                                        List.of(countingStep("then", thenBackendInvocations)),
                                        List.of(countingStep("else", elseBackendInvocations))))
                        .build();

        WorkflowResult result = engine.execute(workflow, WorkflowInputs.empty());

        assertThat(result.completed()).isTrue();
        assertThat(thenBackendInvocations).hasValue(1);
        assertThat(elseBackendInvocations)
                .as("the non-selected branch's backend must never be invoked")
                .hasValue(0);
    }

    // --- BRANCH-008: no hidden re-evaluation, even if the condition itself would flip ------

    @Test
    void branch008ConditionIsNeverReevaluatedEvenIfItWouldFlip() {
        AtomicInteger evaluations = new AtomicInteger();
        AtomicInteger thenCount = new AtomicInteger();
        AtomicInteger elseCount = new AtomicInteger();
        IWorkflowCondition flipping =
                new IWorkflowCondition() {
                    @Override
                    public boolean evaluate(IWorkflowVariables variables) {
                        // First call: true. Any further call (which must never happen): false.
                        return evaluations.getAndIncrement() == 0;
                    }

                    @Override
                    public String describe() {
                        return "flipping";
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
                                        flipping,
                                        List.of(countingStep("then", thenCount)),
                                        List.of(countingStep("else", elseCount))))
                        .build();

        WorkflowResult result = engine.execute(workflow, WorkflowInputs.empty());

        assertThat(result.completed()).isTrue();
        assertThat(evaluations)
                .as("the condition must be evaluated exactly once, never re-evaluated")
                .hasValue(1);
        assertThat(thenCount).hasValue(1);
        assertThat(elseCount).hasValue(0);
    }

    // --- Nested branching --------------------------------------------------------------------

    @Test
    void nestedBranchingSelectsExactlyOneLeafAtEveryDepth() {
        AtomicInteger evalA = new AtomicInteger();
        AtomicInteger evalB = new AtomicInteger();
        AtomicInteger x = new AtomicInteger();
        AtomicInteger y = new AtomicInteger();
        AtomicInteger z = new AtomicInteger();
        IWorkflowStep inner =
                WorkflowSteps.ifElse(
                        "innerB",
                        new CountingCondition(false, evalB),
                        List.of(countingStep("x", x)),
                        List.of(countingStep("y", y)));
        Workflow workflow =
                Workflow.builder("wf")
                        .step(
                                WorkflowSteps.ifElse(
                                        "outerA",
                                        new CountingCondition(true, evalA),
                                        List.of(inner),
                                        List.of(countingStep("z", z))))
                        .build();

        WorkflowResult result = engine.execute(workflow, WorkflowInputs.empty());

        assertThat(result.completed()).isTrue();
        assertThat(evalA).hasValue(1);
        assertThat(evalB).hasValue(1);
        assertThat(x).hasValue(0);
        assertThat(y).hasValue(1);
        assertThat(z).hasValue(0);
        assertThat(result.steps().stream().map(r -> r.stepId().value()))
                .containsExactly("outerA", "innerB", "y");
    }

    // --- Mutation adversarial: world changes after the decision, branch is unaffected ------

    @Test
    void branchSelectionIsUnaffectedByStateMutatedAfterTheDecisionWasCaptured() {
        AtomicInteger evaluations = new AtomicInteger();
        AtomicInteger thenCount = new AtomicInteger();
        AtomicInteger elseCount = new AtomicInteger();
        java.util.concurrent.atomic.AtomicBoolean worldState =
                new java.util.concurrent.atomic.AtomicBoolean(true);
        IWorkflowCondition readsWorldState =
                new IWorkflowCondition() {
                    @Override
                    public boolean evaluate(IWorkflowVariables variables) {
                        evaluations.incrementAndGet();
                        return worldState.get();
                    }

                    @Override
                    public String describe() {
                        return "readsWorldState";
                    }

                    @Override
                    public Set<WorkflowVariable<?>> referencedVariables() {
                        return Set.of();
                    }
                };
        IWorkflowStep thenStepThatSeesMutatedWorld =
                WorkflowSteps.action(
                        "then",
                        variables -> {
                            // The world changed after the decision was captured - the branch
                            // still runs to completion; the condition is not consulted again.
                            worldState.set(false);
                            thenCount.incrementAndGet();
                            return new FakePreparedAction<>(
                                    ActionResults.success("ok"), new AtomicInteger());
                        });
        Workflow workflow =
                Workflow.builder("wf")
                        .step(
                                WorkflowSteps.ifElse(
                                        "branch",
                                        readsWorldState,
                                        List.of(thenStepThatSeesMutatedWorld),
                                        List.of(countingStep("else", elseCount))))
                        .build();

        WorkflowResult result = engine.execute(workflow, WorkflowInputs.empty());

        assertThat(result.completed()).isTrue();
        assertThat(evaluations).hasValue(1);
        assertThat(thenCount).hasValue(1);
        assertThat(elseCount)
                .as("even though worldState now reads false, ELSE must never run")
                .hasValue(0);
        assertThat(worldState).isFalse();
    }

    // --- An action failure inside the selected branch never falls back to the other branch -

    @Test
    void anActionFailureInsideTheSelectedBranchNeverTriggersTheOtherBranch() {
        AtomicInteger evaluations = new AtomicInteger();
        AtomicInteger elseCount = new AtomicInteger();
        Workflow workflow =
                Workflow.builder("wf")
                        .step(
                                WorkflowSteps.ifElse(
                                        "branch",
                                        new CountingCondition(true, evaluations),
                                        List.of(failingStep("then", new AtomicInteger())),
                                        List.of(countingStep("else", elseCount))))
                        .build();

        WorkflowResult result = engine.execute(workflow, WorkflowInputs.empty());

        assertThat(result.completed()).isFalse();
        assertThat(result.failure().orElseThrow().type())
                .isEqualTo(WorkflowFailureType.ACTION_FAILED);
        assertThat(elseCount).as("a failed THEN branch must never fall back to ELSE").hasValue(0);
        assertThat(evaluations).hasValue(1);
    }

    // --- Optional else: false with no else branch is a no-op success -----------------------

    @Test
    void ifThenWithoutElseIsANoOpSuccessWhenConditionIsFalse() {
        AtomicInteger evaluations = new AtomicInteger();
        AtomicInteger thenCount = new AtomicInteger();
        Workflow workflow =
                Workflow.builder("wf")
                        .step(
                                WorkflowSteps.ifThen(
                                        "branch",
                                        new CountingCondition(false, evaluations),
                                        List.of(countingStep("then", thenCount))))
                        .build();

        WorkflowResult result = engine.execute(workflow, WorkflowInputs.empty());

        assertThat(result.completed()).isTrue();
        assertThat(evaluations).hasValue(1);
        assertThat(thenCount).hasValue(0);
        assertThat(result.steps()).hasSize(1);
        assertThat(result.steps().get(0).status()).isEqualTo(WorkflowStepStatus.SUCCEEDED);
    }

    // --- Sensitive branch condition: the secret never leaks through diagnostics ------------

    @Test
    void sensitiveBranchConditionNeverLeaksThroughDiagnosticsEvenOnFailure() {
        Workflow workflow =
                Workflow.builder("wf")
                        .requiredInput(PASSWORD)
                        .step(
                                WorkflowSteps.ifElse(
                                        "branch",
                                        WorkflowConditions.equals(PASSWORD, SECRET_SENTINEL),
                                        List.of(failingStep("then", new AtomicInteger())),
                                        List.of(countingStep("else", new AtomicInteger()))))
                        .build();
        WorkflowInputs inputs = WorkflowInputs.builder().put(PASSWORD, SECRET_SENTINEL).build();

        WorkflowResult result = engine.execute(workflow, inputs);

        assertThat(result.completed()).isFalse();
        assertThat(result.toString()).doesNotContain(SECRET_SENTINEL);
        assertThat(result.steps().get(0).toString())
                .doesNotContain(SECRET_SENTINEL)
                .contains("***");
        assertThat(result.failure().orElseThrow().toString()).doesNotContain(SECRET_SENTINEL);
    }

    // --- Exactly-once regression: WorkflowResult.steps() shape stays well-formed -----------

    @Test
    void conditionalStepResultCarriesTheDecisionAndNoOutputOrActionSummary() {
        AtomicInteger evaluations = new AtomicInteger();
        Workflow workflow =
                Workflow.builder("wf")
                        .step(
                                WorkflowSteps.ifElse(
                                        "branch",
                                        new CountingCondition(true, evaluations),
                                        List.of(countingStep("then", new AtomicInteger())),
                                        List.of(countingStep("else", new AtomicInteger()))))
                        .build();

        WorkflowResult result = engine.execute(workflow, WorkflowInputs.empty());

        WorkflowStepResult branchResult = result.steps().get(0);
        assertThat(branchResult.stepType()).isEqualTo(WorkflowStepType.CONDITIONAL);
        assertThat(branchResult.status()).isEqualTo(WorkflowStepStatus.SUCCEEDED);
        assertThat(branchResult.condition()).isPresent();
        assertThat(branchResult.condition().orElseThrow().outcome()).isTrue();
        assertThat(branchResult.outputVariableName()).isEmpty();
        assertThat(branchResult.actionSummary()).isEmpty();
    }
}
