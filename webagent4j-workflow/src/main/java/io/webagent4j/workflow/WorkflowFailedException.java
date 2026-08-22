package io.webagent4j.workflow;

import java.io.IOException;
import java.io.NotSerializableException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serial;
import java.util.Objects;

/**
 * Optional exception-style projection of a structured, failed {@link WorkflowResult}.
 *
 * <p>Java native serialization is explicitly unsupported because {@link #result()} is required
 * structured state and must never silently disappear during deserialization.
 */
public final class WorkflowFailedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient WorkflowResult result;

    /** Creates a safe exception without embedding raw secret values or exception text. */
    public WorkflowFailedException(WorkflowResult result) {
        super(message(result));
        this.result = Objects.requireNonNull(result, "result");
    }

    /** Returns the structured result that caused this exception. */
    public WorkflowResult result() {
        return result;
    }

    private static String message(WorkflowResult result) {
        WorkflowResult required = Objects.requireNonNull(result, "result");
        return "Workflow "
                + required.workflowId().value()
                + " failed with status "
                + required.status()
                + required.failure().map(f -> ": " + f.safeMessage()).orElse("");
    }

    @Serial
    private void writeObject(ObjectOutputStream ignored) throws IOException {
        throw new NotSerializableException(WorkflowFailedException.class.getName());
    }

    @Serial
    private void readObject(ObjectInputStream ignored) throws IOException, ClassNotFoundException {
        throw new NotSerializableException(WorkflowFailedException.class.getName());
    }
}
