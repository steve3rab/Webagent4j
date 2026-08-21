package io.webagent4j.workflow;

import java.util.Optional;

/**
 * Read-only view of the variables available at one point during workflow execution, handed to
 * condition evaluators ({@link IWorkflowCondition}) and action factories ({@link
 * IWorkflowActionFactory}).
 *
 * <p>Explicit typed retrieval - {@link #require} and {@link #find} - always returns the real value,
 * including for a secret variable: an executing step must be able to intentionally read a secret to
 * type it into a form. That is not considered a leak, since it is a deliberate, typed, programmatic
 * read - the security boundary is between this explicit retrieval and any incidental rendering (see
 * {@code docs/workflow.md#secret-masking}).
 */
public interface IWorkflowVariables {

    /**
     * Returns the current value of {@code variable}.
     *
     * @throws WorkflowVariableMissingException if {@code variable} is not present
     */
    <T> T require(WorkflowVariable<T> variable);

    /** Returns the current value of {@code variable}, if present. */
    <T> Optional<T> find(WorkflowVariable<T> variable);

    /** Returns whether {@code variable} currently has a value. */
    boolean exists(WorkflowVariable<?> variable);
}
