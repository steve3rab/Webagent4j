package io.webagent4j.workflow;

import java.util.Objects;
import java.util.Optional;

/**
 * Shared identity/condition storage and the internal execution contract for every built-in workflow
 * step - not public API, reached only through {@link WorkflowSteps}' factory methods.
 *
 * <p>This class is {@code sealed} and package-private together with {@link IWorkflowStep}: since
 * {@code IWorkflowStep permits AWorkflowStep} and only {@link ActionWorkflowStep}, {@link
 * AssignWorkflowStep}, and {@link ConditionalWorkflowStep} may extend it, {@link WorkflowEngine}
 * can downcast any {@code IWorkflowStep} it receives to {@code AWorkflowStep} with the guarantee -
 * enforced by the compiler and the JVM, not by convention - that the cast can never fail.
 */
abstract sealed class AWorkflowStep implements IWorkflowStep
        permits ActionWorkflowStep, AssignWorkflowStep, ConditionalWorkflowStep {

    private final WorkflowStepId id;
    private final IWorkflowCondition condition;

    AWorkflowStep(WorkflowStepId id, IWorkflowCondition condition) {
        this.id = Objects.requireNonNull(id, "id");
        this.condition = condition;
    }

    @Override
    public final WorkflowStepId id() {
        return id;
    }

    @Override
    public final Optional<IWorkflowCondition> condition() {
        return Optional.ofNullable(condition);
    }

    @Override
    public final IWorkflowStep when(IWorkflowCondition condition) {
        return withCondition(Objects.requireNonNull(condition, "condition"));
    }

    /** Returns a copy of this step with {@code condition} attached. */
    abstract AWorkflowStep withCondition(IWorkflowCondition condition);

    /** Returns this step's broad category. */
    abstract WorkflowStepType stepType();

    /** Returns the variable this step publishes on success, if any. */
    abstract Optional<WorkflowVariable<?>> outputVariable();

    /**
     * Runs this step exactly once against the current execution variables.
     *
     * <p>Implementations must never throw for an expected failure (a factory exception, an action
     * failure, a type mismatch) - those become a {@link StepRunOutcome#failure}. Only a genuinely
     * unexpected condition may propagate as an exception, which {@link WorkflowEngine} still
     * catches and converts to a safe {@link WorkflowFailureType#STEP_EXCEPTION}.
     */
    abstract StepRunOutcome run(IWorkflowVariables variables);
}
