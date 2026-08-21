package io.webagent4j.workflow;

/**
 * Thrown by {@link IWorkflowVariables#require(WorkflowVariable)} when the requested variable has no
 * value at the point of the call.
 *
 * <p>The message names only the missing variable, never any value, so it is always safe to render
 * incidentally. {@link WorkflowEngine} classifies this exception differently depending on where it
 * was thrown: during condition evaluation it becomes {@link
 * WorkflowFailureType#CONDITION_EVALUATION_FAILED}; during step execution (an action factory or a
 * custom step reading a variable) it becomes {@link WorkflowFailureType#MISSING_VARIABLE}.
 */
public final class WorkflowVariableMissingException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient WorkflowVariable<?> variable;

    /** Creates an exception naming the missing {@code variable}. */
    public WorkflowVariableMissingException(WorkflowVariable<?> variable) {
        super("variable '" + variable.name() + "' has no value");
        this.variable = variable;
    }

    /** Returns the variable that was missing. */
    public WorkflowVariable<?> variable() {
        return variable;
    }
}
