package io.webagent4j.workflow;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Bounded, deterministic loop workflow step - not public API. Produced only by {@link
 * WorkflowSteps#loop}.
 *
 * <p>Like {@link ConditionalWorkflowStep}, the condition carried here (in the inherited {@code
 * condition} slot) is not an optional skip-guard - it is the mandatory continuation check: {@link
 * WorkflowEngine} evaluates it at most once per iteration attempt, before that iteration's body
 * ever runs, and never re-evaluates it while running the body it already authorized (see {@code
 * docs/workflow.md#bounded-loops}). Because that single condition slot already carries this
 * mandatory meaning, {@link #withCondition} - the mechanism behind the generic {@link
 * IWorkflowStep#when} guard every other step supports - is not supported here, exactly as {@link
 * ConditionalWorkflowStep} documents for the same reason.
 *
 * <p>{@link #maxIterations()} is a mandatory, framework-bounded declaration - never inferred, never
 * silently capped - checked by {@link Workflow.Builder#build()} against {@link
 * Workflow#MAX_LOOP_ITERATIONS}. Reaching it while {@link #continueCondition()} still evaluates
 * {@code true} is a workflow failure ({@link WorkflowFailureType#LOOP_ITERATION_LIMIT_EXCEEDED}),
 * never a silently accepted stop - a bounded loop is an explicit, observable control structure,
 * never a disguised repeat-until-success mechanism.
 *
 * <p>{@link #body()} may itself contain further {@link ConditionalWorkflowStep}s or {@code
 * LoopWorkflowStep}s, but only up to {@link Workflow#MAX_CONTROL_FLOW_NESTING_DEPTH} combined
 * levels of control-flow nesting - {@link Workflow.Builder#build()} rejects a deeper definition
 * before it can ever become an executable {@link Workflow}.
 */
final class LoopWorkflowStep extends AWorkflowStep {

    private final List<IWorkflowStep> body;
    private final int maxIterations;

    LoopWorkflowStep(
            WorkflowStepId id,
            IWorkflowCondition continueCondition,
            int maxIterations,
            List<IWorkflowStep> body) {
        super(id, Objects.requireNonNull(continueCondition, "continueCondition"));
        this.body = List.copyOf(Objects.requireNonNull(body, "body"));
        this.maxIterations = maxIterations;
    }

    /** Returns the mandatory continuation condition - never absent for this step type. */
    IWorkflowCondition continueCondition() {
        return condition().orElseThrow();
    }

    /**
     * Returns the steps to run, in order, for each iteration this loop is authorized to attempt.
     */
    List<IWorkflowStep> body() {
        return body;
    }

    /**
     * Returns the declared maximum number of iterations this loop may attempt - not yet validated
     * against {@link Workflow#MAX_LOOP_ITERATIONS}; see {@link Workflow.Builder#build()}.
     */
    int maxIterations() {
        return maxIterations;
    }

    @Override
    WorkflowStepType stepType() {
        return WorkflowStepType.LOOP;
    }

    @Override
    Optional<WorkflowVariable<?>> outputVariable() {
        return Optional.empty();
    }

    @Override
    AWorkflowStep withCondition(IWorkflowCondition condition) {
        throw new UnsupportedOperationException(
                "a loop step's condition is its mandatory continuation check, not an optional"
                        + " guard - when(...) is not supported on a step built by loop(...); express"
                        + " an outer guard as a separate step or fold it into the continuation"
                        + " condition itself");
    }

    @Override
    StepRunOutcome run(IWorkflowVariables variables) {
        throw new IllegalStateException(
                "a loop step is executed by WorkflowEngine's own iteration logic, never through"
                        + " AWorkflowStep#run");
    }
}
