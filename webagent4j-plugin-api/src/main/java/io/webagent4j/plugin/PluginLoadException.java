package io.webagent4j.plugin;

import java.io.IOException;
import java.io.NotSerializableException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serial;
import java.util.Objects;

/**
 * Fail-closed result of an explicit plugin discovery attempt.
 *
 * <p>Java native serialization is explicitly unsupported because the structured failure is the
 * authoritative contract and must never disappear during a partial serialization round trip.
 */
public final class PluginLoadException extends IllegalStateException {

    private static final long serialVersionUID = 1L;

    private final transient PluginLoadFailure failure;

    /** Creates an exception containing only a safe framework-owned diagnostic. */
    PluginLoadException(PluginLoadFailure failure) {
        super(Objects.requireNonNull(failure, "failure").safeMessage());
        this.failure = failure;
    }

    /** Returns the structured load failure. */
    public PluginLoadFailure failure() {
        return failure;
    }

    @Serial
    private void writeObject(ObjectOutputStream ignored) throws IOException {
        throw new NotSerializableException(PluginLoadException.class.getName());
    }

    @Serial
    private void readObject(ObjectInputStream ignored) throws IOException, ClassNotFoundException {
        throw new NotSerializableException(PluginLoadException.class.getName());
    }
}
