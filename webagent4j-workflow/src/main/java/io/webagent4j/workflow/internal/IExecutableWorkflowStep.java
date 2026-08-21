package io.webagent4j.workflow.internal;

import io.webagent4j.workflow.IWorkflowStep;
import io.webagent4j.workflow.IWorkflowVariables;
import io.webagent4j.workflow.WorkflowStepType;
import io.webagent4j.workflow.WorkflowVariable;
import java.util.Optional;

/**
 * Internal execution contract every concrete {@code WorkflowSteps}-produced step implements, in
 * addition to the public, structural {@link IWorkflowStep} - not public API.
 *
 * <p>{@code WorkflowEngine} downcasts each {@link IWorkflowStep} it receives from a {@code
 * Workflow} definition to this interface to actually run it; nothing outside this module ever needs
 * to.
 */
public interface IExecutableWorkflowStep extends IWorkflowStep {

    /** Returns this step's broad category. */
    WorkflowStepType stepType();

    /** Returns the variable this step publishes on success, if any. */
    Optional<WorkflowVariable<?>> outputVariable();

    /**
     * Runs this step exactly once against the current execution variables.
     *
     * <p>Implementations must never throw for an expected failure (a factory exception, an action
     * failure, a type mismatch) - those become a {@link StepRunOutcome#failure}. Only a genuinely
     * unexpected condition may propagate as an exception, which {@code WorkflowEngine} still
     * catches and converts to a safe {@link
     * io.webagent4j.workflow.WorkflowFailureType#STEP_EXCEPTION}.
     */
    StepRunOutcome run(IWorkflowVariables variables);
}
