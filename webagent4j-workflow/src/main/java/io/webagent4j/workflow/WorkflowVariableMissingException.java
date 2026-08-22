package io.webagent4j.workflow;

import java.io.IOException;
import java.io.NotSerializableException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serial;
import java.util.Objects;

/**
 * Thrown by {@link IWorkflowVariables#require(WorkflowVariable)} when the requested variable has no
 * value at the point of the call.
 *
 * <p>The message names only the missing variable, never any value, so it is always safe to render
 * incidentally. {@link WorkflowEngine} classifies this exception differently depending on where it
 * was thrown: during condition evaluation it becomes {@link
 * WorkflowFailureType#CONDITION_EVALUATION_FAILED}; during step execution (an action factory or a
 * custom step reading a variable) it becomes {@link WorkflowFailureType#MISSING_VARIABLE}. Java
 * native serialization is explicitly unsupported because {@link #variable()} is required structured
 * state.
 */
public final class WorkflowVariableMissingException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient WorkflowVariable<?> variable;

    /** Creates an exception naming the missing {@code variable}. */
    public WorkflowVariableMissingException(WorkflowVariable<?> variable) {
        super(message(variable));
        this.variable = Objects.requireNonNull(variable, "variable");
    }

    /** Returns the variable that was missing. */
    public WorkflowVariable<?> variable() {
        return variable;
    }

    private static String message(WorkflowVariable<?> variable) {
        return "variable '"
                + Objects.requireNonNull(variable, "variable").name()
                + "' has no value";
    }

    @Serial
    private void writeObject(ObjectOutputStream ignored) throws IOException {
        throw new NotSerializableException(WorkflowVariableMissingException.class.getName());
    }

    @Serial
    private void readObject(ObjectInputStream ignored) throws IOException, ClassNotFoundException {
        throw new NotSerializableException(WorkflowVariableMissingException.class.getName());
    }
}
