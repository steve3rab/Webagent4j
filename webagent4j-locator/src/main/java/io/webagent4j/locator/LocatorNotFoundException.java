package io.webagent4j.locator;

import io.webagent4j.common.LocatorException;
import java.io.IOException;
import java.io.NotSerializableException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serial;
import java.util.Objects;

/**
 * Indicates that no compatible candidate appeared before the locator timeout.
 *
 * <p>Java native serialization is explicitly unsupported because structured diagnostics must never
 * silently disappear during deserialization.
 */
public final class LocatorNotFoundException extends LocatorException
        implements io.webagent4j.common.ILocatorFailure {

    private static final long serialVersionUID = 1L;

    private final transient LocatorDiagnostics diagnostics;
    private final LocatorResolutionStatus status;

    /** Creates an exception containing a rendered locator diagnostic. */
    public LocatorNotFoundException(String message) {
        super(message);
        this.diagnostics = null;
        this.status = LocatorResolutionStatus.UNRESOLVABLE;
    }

    /** Creates an exception retaining structured locator diagnostics. */
    public LocatorNotFoundException(String message, LocatorDiagnostics diagnostics) {
        this(message, diagnostics, LocatorResolutionStatus.UNRESOLVABLE);
    }

    /** Creates an exception retaining diagnostics and its formal safe failure outcome. */
    public LocatorNotFoundException(
            String message, LocatorDiagnostics diagnostics, LocatorResolutionStatus status) {
        super(message);
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
        this.status = Objects.requireNonNull(status, "status");
        if (status == LocatorResolutionStatus.RESOLVED
                || status == LocatorResolutionStatus.AMBIGUOUS) {
            throw new IllegalArgumentException("not-found status must represent a safe failure");
        }
    }

    /** Returns structured diagnostics when this exception was raised by the locator engine. */
    public java.util.Optional<LocatorDiagnostics> diagnostics() {
        return java.util.Optional.ofNullable(diagnostics);
    }

    /** Returns the formal safe failure outcome. */
    public LocatorResolutionStatus status() {
        return status;
    }

    @Override
    public boolean isNotFound() {
        return true;
    }

    @Serial
    private void writeObject(ObjectOutputStream ignored) throws IOException {
        throw new NotSerializableException(LocatorNotFoundException.class.getName());
    }

    @Serial
    private void readObject(ObjectInputStream ignored) throws IOException, ClassNotFoundException {
        throw new NotSerializableException(LocatorNotFoundException.class.getName());
    }
}
