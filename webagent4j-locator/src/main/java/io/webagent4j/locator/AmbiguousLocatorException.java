package io.webagent4j.locator;

import io.webagent4j.common.LocatorException;
import java.io.IOException;
import java.io.NotSerializableException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serial;
import java.util.Objects;

/**
 * Indicates that single-result resolution found materially equivalent best candidates.
 *
 * <p>Java native serialization is explicitly unsupported because structured diagnostics must never
 * silently disappear during deserialization.
 */
public final class AmbiguousLocatorException extends LocatorException
        implements io.webagent4j.common.ILocatorFailure {

    private static final long serialVersionUID = 1L;

    private final transient LocatorDiagnostics diagnostics;

    /** Creates an exception containing the ranked ambiguous candidates. */
    public AmbiguousLocatorException(String message) {
        super(message);
        this.diagnostics = null;
    }

    /** Creates an exception retaining structured ambiguity diagnostics. */
    public AmbiguousLocatorException(String message, LocatorDiagnostics diagnostics) {
        super(message);
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
    }

    /** Returns structured diagnostics when this exception was raised by the locator engine. */
    public java.util.Optional<LocatorDiagnostics> diagnostics() {
        return java.util.Optional.ofNullable(diagnostics);
    }

    /** Returns the formal safe ambiguity outcome. */
    public LocatorResolutionStatus status() {
        return LocatorResolutionStatus.AMBIGUOUS;
    }

    @Override
    public boolean isAmbiguous() {
        return true;
    }

    @Serial
    private void writeObject(ObjectOutputStream ignored) throws IOException {
        throw new NotSerializableException(AmbiguousLocatorException.class.getName());
    }

    @Serial
    private void readObject(ObjectInputStream ignored) throws IOException, ClassNotFoundException {
        throw new NotSerializableException(AmbiguousLocatorException.class.getName());
    }
}
