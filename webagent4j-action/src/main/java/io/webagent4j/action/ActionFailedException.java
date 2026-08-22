package io.webagent4j.action;

import java.io.IOException;
import java.io.NotSerializableException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serial;
import java.util.Objects;

/**
 * Optional exception-style projection of a structured unsuccessful action result.
 *
 * <p>Java native serialization is explicitly unsupported because {@link #result()} is required
 * structured state and must never silently disappear during deserialization.
 */
public final class ActionFailedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient ActionResult<?> result;

    /** Creates a safe exception without embedding backend exception text or secret values. */
    public ActionFailedException(ActionResult<?> result) {
        super(message(result));
        this.result = Objects.requireNonNull(result, "result");
    }

    /** Returns the structured result that caused this exception. */
    public ActionResult<?> result() {
        return result;
    }

    private static String message(ActionResult<?> result) {
        ActionResult<?> required = Objects.requireNonNull(result, "result");
        return "Action " + required.actionId().value() + " failed with status " + required.status();
    }

    @Serial
    private void writeObject(ObjectOutputStream ignored) throws IOException {
        throw new NotSerializableException(ActionFailedException.class.getName());
    }

    @Serial
    private void readObject(ObjectInputStream ignored) throws IOException, ClassNotFoundException {
        throw new NotSerializableException(ActionFailedException.class.getName());
    }
}
