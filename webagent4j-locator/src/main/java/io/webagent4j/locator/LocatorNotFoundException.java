package io.webagent4j.locator;

import io.webagent4j.common.LocatorException;

/** Indicates that no compatible candidate appeared before the locator timeout. */
public final class LocatorNotFoundException extends LocatorException {

    private static final long serialVersionUID = 1L;

    private final transient LocatorDiagnostics diagnostics;
    private final LocatorResolutionStatus status;

    /** Creates an exception containing a rendered locator diagnostic. */
    public LocatorNotFoundException(String message) {
        this(message, null, LocatorResolutionStatus.UNRESOLVABLE);
    }

    /** Creates an exception retaining structured locator diagnostics. */
    public LocatorNotFoundException(String message, LocatorDiagnostics diagnostics) {
        this(message, diagnostics, LocatorResolutionStatus.UNRESOLVABLE);
    }

    /** Creates an exception retaining diagnostics and its formal safe failure outcome. */
    public LocatorNotFoundException(
            String message, LocatorDiagnostics diagnostics, LocatorResolutionStatus status) {
        super(message);
        this.diagnostics = diagnostics;
        this.status = java.util.Objects.requireNonNull(status, "status");
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
}
