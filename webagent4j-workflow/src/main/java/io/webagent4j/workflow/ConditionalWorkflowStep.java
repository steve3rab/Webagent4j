package io.webagent4j.workflow;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Deterministic if/else workflow step - not public API. Produced only by {@link
 * WorkflowSteps#ifElse} and {@link WorkflowSteps#ifThen}.
 *
 * <p>Unlike every other {@link AWorkflowStep}, the condition carried here (in the inherited {@code
 * condition} slot) is not an optional skip-guard - it is the mandatory branch selector: {@link
 * WorkflowEngine} evaluates it exactly once and then executes exactly one of {@link #thenSteps()}
 * or {@link #elseSteps()}, never both, never neither, and never re-evaluates it while running the
 * selected branch (see {@code docs/workflow.md#branching}). Because that single condition slot
 * already carries this mandatory meaning, {@link #withCondition} - the mechanism behind the generic
 * {@link IWorkflowStep#when} guard every other step supports - is not supported here: it would be
 * ambiguous whether a second condition attached that way guards the whole conditional or replaces
 * the branch selector, so it throws rather than silently picking one reading.
 *
 * <p>{@link #thenSteps()}/{@link #elseSteps()} may themselves contain further {@code
 * ConditionalWorkflowStep}s, but only up to {@link Workflow#MAX_CONDITIONAL_NESTING_DEPTH} levels
 * of nesting - {@link Workflow.Builder#build()} rejects a deeper definition before it can ever
 * become an executable {@link Workflow}, which is also what keeps this type's own recursive
 * validation and {@link WorkflowEngine}'s recursive execution bounded without either one needing a
 * separate depth check of its own.
 */
final class ConditionalWorkflowStep extends AWorkflowStep {

    private final List<IWorkflowStep> thenSteps;
    private final List<IWorkflowStep> elseSteps;

    ConditionalWorkflowStep(
            WorkflowStepId id,
            IWorkflowCondition branchCondition,
            List<IWorkflowStep> thenSteps,
            List<IWorkflowStep> elseSteps) {
        super(id, Objects.requireNonNull(branchCondition, "branchCondition"));
        this.thenSteps = List.copyOf(Objects.requireNonNull(thenSteps, "thenSteps"));
        this.elseSteps = elseSteps == null ? null : List.copyOf(Objects.requireNonNull(elseSteps));
    }

    /** Returns the mandatory branch-selector condition - never absent for this step type. */
    IWorkflowCondition branchCondition() {
        return condition().orElseThrow();
    }

    /** Returns the steps to run, in order, when {@link #branchCondition()} evaluates to true. */
    List<IWorkflowStep> thenSteps() {
        return thenSteps;
    }

    /**
     * Returns the steps to run, in order, when {@link #branchCondition()} evaluates to false - or
     * empty if no {@code elseSteps} were declared, in which case a false decision is a no-op
     * success for this step.
     */
    Optional<List<IWorkflowStep>> elseSteps() {
        return Optional.ofNullable(elseSteps);
    }

    @Override
    WorkflowStepType stepType() {
        return WorkflowStepType.CONDITIONAL;
    }

    @Override
    Optional<WorkflowVariable<?>> outputVariable() {
        return Optional.empty();
    }

    @Override
    AWorkflowStep withCondition(IWorkflowCondition condition) {
        throw new UnsupportedOperationException(
                "a conditional step's condition is its mandatory branch selector, not an optional"
                        + " guard - when(...) is not supported on a step built by ifElse(...)/"
                        + "ifThen(...); express an outer guard as a separate step or fold it into"
                        + " the branch condition itself");
    }

    @Override
    StepRunOutcome run(IWorkflowVariables variables) {
        throw new IllegalStateException(
                "a conditional step is executed by WorkflowEngine's own branch-selection logic,"
                        + " never through AWorkflowStep#run");
    }
}
