package io.webagent4j.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.webagent4j.action.ActionFailureType;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Deterministic Bounded Workflow Loop invariant matrix: exactly-once-per-iteration condition
 * evaluation, no hidden retry, fail-closed at the iteration bound, deadline/interruption boundaries
 * mirroring {@link ConditionalWorkflowStep}'s own, and zero side effects from an iteration that
 * never started. See {@code docs/workflow.md#bounded-loops}.
 */
class WorkflowLoopEngineTest {

    private final WorkflowEngine engine = new WorkflowEngine();

    @AfterEach
    void clearInterruptFlag() {
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

    /** A condition that returns {@code true} for the first {@code trueCount} calls, then false. */
    private static final class CountingUntilFalseCondition implements IWorkflowCondition {
        private final int trueCount;
        private final AtomicInteger evaluations;

        CountingUntilFalseCondition(int trueCount, AtomicInteger evaluations) {
            this.trueCount = trueCount;
            this.evaluations = evaluations;
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

    private static final class AlwaysTrueCondition implements IWorkflowCondition {
        private final AtomicInteger evaluations;

        AlwaysTrueCondition(AtomicInteger evaluations) {
            this.evaluations = evaluations;
        }

        @Override
        public boolean evaluate(IWorkflowVariables variables) {
            evaluations.incrementAndGet();
            return true;
        }

        @Override
        public String describe() {
            return "alwaysTrue";
        }

        @Override
        public Set<WorkflowVariable<?>> referencedVariables() {
            return Set.of();
        }
    }

    // --- LOOP-001: condition false immediately - zero iterations, no-op success ------------

    @Test
    void loop001ConditionFalseImmediatelyRunsZeroIterations() {
        AtomicInteger evaluations = new AtomicInteger();
        AtomicInteger bodyExecutions = new AtomicInteger();
        Workflow workflow =
                Workflow.builder("wf")
                        .step(
                                WorkflowSteps.loop(
                                        "loop",
                                        new CountingUntilFalseCondition(0, evaluations),
                                        5,
                                        List.of(countingStep("body", bodyExecutions))))
                        .build();

        WorkflowResult result = engine.execute(workflow, WorkflowInputs.empty());

        assertThat(result.completed()).isTrue();
        assertThat(evaluations).hasValue(1);
        assertThat(bodyExecutions).hasValue(0);
        assertThat(result.steps()).hasSize(2); // LOOP wrapper + one LOOP_ITERATION(false)
        assertThat(result.steps().get(0).stepType()).isEqualTo(WorkflowStepType.LOOP);
        assertThat(result.steps().get(1).stepType()).isEqualTo(WorkflowStepType.LOOP_ITERATION);
        assertThat(result.steps().get(1).condition().orElseThrow().outcome()).isFalse();
    }

    // --- LOOP-002: one-then-false - exactly one iteration -----------------------------------

    @Test
    void loop002OneThenFalseRunsExactlyOneIteration() {
        AtomicInteger evaluations = new AtomicInteger();
        AtomicInteger bodyExecutions = new AtomicInteger();
        Workflow workflow =
                Workflow.builder("wf")
                        .step(
                                WorkflowSteps.loop(
                                        "loop",
                                        new CountingUntilFalseCondition(1, evaluations),
                                        5,
                                        List.of(countingStep("body", bodyExecutions))))
                        .build();

        WorkflowResult result = engine.execute(workflow, WorkflowInputs.empty());

        assertThat(result.completed()).isTrue();
        assertThat(evaluations).hasValue(2);
        assertThat(bodyExecutions).hasValue(1);
        // LOOP wrapper + iteration0(true)+body + iteration1(false) = 4 flat entries
        assertThat(result.steps()).hasSize(4);
    }

    // --- LOOP-003: N-then-false - exactly N iterations, no more, no fewer -------------------

    @Test
    void loop003NThenFalseRunsExactlyNIterations() {
        AtomicInteger evaluations = new AtomicInteger();
        AtomicInteger bodyExecutions = new AtomicInteger();
        Workflow workflow =
                Workflow.builder("wf")
                        .step(
                                WorkflowSteps.loop(
                                        "loop",
                                        new CountingUntilFalseCondition(3, evaluations),
                                        10,
                                        List.of(countingStep("body", bodyExecutions))))
                        .build();

        WorkflowResult result = engine.execute(workflow, WorkflowInputs.empty());

        assertThat(result.completed()).isTrue();
        assertThat(evaluations).hasValue(4);
        assertThat(bodyExecutions).hasValue(3);
    }

    // --- LOOP-004: condition still true at the bound fails closed ---------------------------

    @Test
    void loop004ConditionStillTrueAtMaxIterationsFailsClosed() {
        AtomicInteger evaluations = new AtomicInteger();
        AtomicInteger bodyExecutions = new AtomicInteger();
        Workflow workflow =
                Workflow.builder("wf")
                        .step(
                                WorkflowSteps.loop(
                                        "loop",
                                        new AlwaysTrueCondition(evaluations),
                                        5,
                                        List.of(countingStep("body", bodyExecutions))))
                        .build();

        WorkflowResult result = engine.execute(workflow, WorkflowInputs.empty());

        assertThat(result.completed()).isFalse();
        assertThat(result.failure().orElseThrow().type())
                .isEqualTo(WorkflowFailureType.LOOP_ITERATION_LIMIT_EXCEEDED);
        // Exactly maxIterations (5) bodies ran, never a 6th.
        assertThat(bodyExecutions).hasValue(5);
        assertThat(evaluations).hasValue(6);
    }

    // --- LOOP-005: condition evaluation itself throws - fail-closed, never coerced to false -

    @Test
    void loop005ConditionThrowsFailsClosedNeverCoercedToFalse() {
        AtomicInteger bodyExecutions = new AtomicInteger();
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
                                WorkflowSteps.loop(
                                        "loop",
                                        throwing,
                                        5,
                                        List.of(countingStep("body", bodyExecutions))))
                        .build();

        WorkflowResult result = engine.execute(workflow, WorkflowInputs.empty());

        assertThat(result.completed()).isFalse();
        assertThat(result.failure().orElseThrow().type())
                .isEqualTo(WorkflowFailureType.CONDITION_EVALUATION_FAILED);
        assertThat(bodyExecutions).hasValue(0);
    }

    // --- LOOP-006: a body failure at iteration k stops the loop, no iteration k+1 -----------

    @Test
    void loop006BodyFailureAtIterationStopsTheLoopWithNoFurtherIteration() {
        AtomicInteger evaluations = new AtomicInteger();
        AtomicInteger failingExecutions = new AtomicInteger();
        Workflow workflow =
                Workflow.builder("wf")
                        .step(
                                WorkflowSteps.loop(
                                        "loop",
                                        new AlwaysTrueCondition(evaluations),
                                        5,
                                        List.of(failingStep("body", failingExecutions))))
                        .build();

        WorkflowResult result = engine.execute(workflow, WorkflowInputs.empty());

        assertThat(result.completed()).isFalse();
        assertThat(result.failure().orElseThrow().type())
                .isEqualTo(WorkflowFailureType.ACTION_FAILED);
        assertThat(failingExecutions).hasValue(1);
        assertThat(evaluations).hasValue(1);
    }

    // --- LOOP-007: pre-interrupted before the loop is ever reached --------------------------

    @Test
    void loop007PreInterruptedBeforeLoopNeverEvaluatesOrRuns() {
        AtomicInteger evaluations = new AtomicInteger();
        AtomicInteger bodyExecutions = new AtomicInteger();
        Workflow workflow =
                Workflow.builder("wf")
                        .step(
                                WorkflowSteps.loop(
                                        "loop",
                                        new AlwaysTrueCondition(evaluations),
                                        5,
                                        List.of(countingStep("body", bodyExecutions))))
                        .build();

        Thread.currentThread().interrupt();
        WorkflowResult result = engine.execute(workflow, WorkflowInputs.empty());

        assertThat(Thread.currentThread().isInterrupted()).isTrue();
        assertThat(result.completed()).isFalse();
        assertThat(result.failure().orElseThrow().type())
                .isEqualTo(WorkflowFailureType.LOOP_STEP_INTERRUPTED);
        assertThat(evaluations).hasValue(0);
        assertThat(bodyExecutions).hasValue(0);
    }

    // --- LOOP-008: interrupted after the decision is captured, before the body starts ------

    @Test
    void loop008InterruptedAfterDecisionBeforeBodyStartsBlocksTheBody() {
        AtomicInteger evaluations = new AtomicInteger();
        AtomicInteger bodyExecutions = new AtomicInteger();
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
                                WorkflowSteps.loop(
                                        "loop",
                                        interruptAfterDeciding,
                                        5,
                                        List.of(countingStep("body", bodyExecutions))))
                        .build();

        WorkflowResult result = engine.execute(workflow, WorkflowInputs.empty());

        assertThat(result.completed()).isFalse();
        assertThat(result.failure().orElseThrow().type())
                .isEqualTo(WorkflowFailureType.LOOP_STEP_INTERRUPTED);
        assertThat(evaluations).hasValue(1);
        assertThat(bodyExecutions).hasValue(0);
    }

    // --- LOOP-009: no retry after a body failure - the failed step never runs again --------

    @Test
    void loop009NoRetryAfterBodyFailure() {
        AtomicInteger evaluations = new AtomicInteger();
        AtomicInteger attempts = new AtomicInteger();
        IWorkflowStep failingOnce =
                WorkflowSteps.action(
                        "body",
                        variables -> {
                            attempts.incrementAndGet();
                            return new FakePreparedAction<>(
                                    ActionResults.failure(
                                            ActionFailureType.TARGET_NOT_FOUND, "gone"),
                                    new AtomicInteger());
                        });
        Workflow workflow =
                Workflow.builder("wf")
                        .step(
                                WorkflowSteps.loop(
                                        "loop",
                                        new AlwaysTrueCondition(evaluations),
                                        5,
                                        List.of(failingOnce)))
                        .build();

        WorkflowResult result = engine.execute(workflow, WorkflowInputs.empty());

        assertThat(result.completed()).isFalse();
        assertThat(attempts).as("the failed body step must never be retried").hasValue(1);
    }

    // --- LOOP-010: nested loop within bounds succeeds, no StackOverflowError, IDs stay unique

    @Test
    void loop010NestedLoopWithinBoundsRunsCleanlyWithUniqueQualifiedStepIds() {
        AtomicInteger outerEvaluations = new AtomicInteger();
        AtomicInteger innerBodyExecutions = new AtomicInteger();
        // The same IWorkflowCondition instance backs every outer iteration's inner loop, exactly
        // as one declared step is reused across iterations - so its internal counter is never
        // magically reset per outer iteration: outer iteration 0 exhausts it (2 true then false,
        // 3 evaluations), and every later outer iteration's inner loop immediately reads false
        // from that same, already-exhausted counter. This is itself the invariant under test: no
        // hidden per-iteration reset of anything the workflow author did not explicitly reset.
        IWorkflowStep innerLoop =
                WorkflowSteps.loop(
                        "inner",
                        new CountingUntilFalseCondition(2, new AtomicInteger()),
                        5,
                        List.of(countingStep("inner-body", innerBodyExecutions)));
        Workflow workflow =
                Workflow.builder("wf")
                        .step(
                                WorkflowSteps.loop(
                                        "outer",
                                        new CountingUntilFalseCondition(3, outerEvaluations),
                                        5,
                                        List.of(innerLoop)))
                        .build();

        WorkflowResult result = engine.execute(workflow, WorkflowInputs.empty());

        assertThat(result.completed()).isTrue();
        assertThat(innerBodyExecutions).hasValue(2);
        // Every flat step ID must be unique despite the same declared IDs repeating per iteration.
        assertThat(result.steps().stream().map(r -> r.stepId().value()).distinct().count())
                .isEqualTo(result.steps().size());
    }

    // --- LOOP-011/012/013: maxIterations bound validation -----------------------------------

    @Test
    void loop011ZeroMaxIterationsIsRejectedAtBuild() {
        Workflow.Builder builder =
                Workflow.builder("wf")
                        .step(
                                WorkflowSteps.loop(
                                        "loop",
                                        new AlwaysTrueCondition(new AtomicInteger()),
                                        0,
                                        List.of(countingStep("body", new AtomicInteger()))));

        assertThatThrownBy(builder::build).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void loop012NegativeMaxIterationsIsRejectedAtBuild() {
        Workflow.Builder builder =
                Workflow.builder("wf")
                        .step(
                                WorkflowSteps.loop(
                                        "loop",
                                        new AlwaysTrueCondition(new AtomicInteger()),
                                        -1,
                                        List.of(countingStep("body", new AtomicInteger()))));

        assertThatThrownBy(builder::build).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void loop013FrameworkMaximumPlusOneIsRejectedAtBuild() {
        Workflow.Builder builder =
                Workflow.builder("wf")
                        .step(
                                WorkflowSteps.loop(
                                        "loop",
                                        new AlwaysTrueCondition(new AtomicInteger()),
                                        Workflow.MAX_LOOP_ITERATIONS + 1,
                                        List.of(countingStep("body", new AtomicInteger()))));

        assertThatThrownBy(builder::build)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(String.valueOf(Workflow.MAX_LOOP_ITERATIONS));
    }

    @Test
    void loop013bFrameworkMaximumItselfIsAccepted() {
        Workflow workflow =
                Workflow.builder("wf")
                        .step(
                                WorkflowSteps.loop(
                                        "loop",
                                        new CountingUntilFalseCondition(0, new AtomicInteger()),
                                        Workflow.MAX_LOOP_ITERATIONS,
                                        List.of(countingStep("body", new AtomicInteger()))))
                        .build();

        assertThat(workflow).isNotNull();
    }

    // --- LOOP-014: an empty body is rejected at the factory, not at build() ----------------

    @Test
    void loop014EmptyBodyIsRejectedAtTheFactory() {
        assertThatThrownBy(
                        () ->
                                WorkflowSteps.loop(
                                        "loop",
                                        new AlwaysTrueCondition(new AtomicInteger()),
                                        5,
                                        List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // --- LOOP-015: the LOOP wrapper's own result carries no condition/output/action --------

    @Test
    void loop015WrapperResultCarriesNoConditionOutputOrActionSummary() {
        Workflow workflow =
                Workflow.builder("wf")
                        .step(
                                WorkflowSteps.loop(
                                        "loop",
                                        new CountingUntilFalseCondition(1, new AtomicInteger()),
                                        5,
                                        List.of(countingStep("body", new AtomicInteger()))))
                        .build();

        WorkflowResult result = engine.execute(workflow, WorkflowInputs.empty());

        WorkflowStepResult wrapper = result.steps().get(0);
        assertThat(wrapper.stepType()).isEqualTo(WorkflowStepType.LOOP);
        assertThat(wrapper.status()).isEqualTo(WorkflowStepStatus.SUCCEEDED);
        assertThat(wrapper.condition()).isEmpty();
        assertThat(wrapper.outputVariableName()).isEmpty();
        assertThat(wrapper.actionSummary()).isEmpty();
    }
}
