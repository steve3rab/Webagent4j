package io.webagent4j.locator;

import io.webagent4j.common.LocatorException;

/** Indicates that single-result resolution found materially equivalent best candidates. */
public final class AmbiguousLocatorException extends LocatorException {

    private static final long serialVersionUID = 1L;

    private final transient LocatorDiagnostics diagnostics;

    /** Creates an exception containing the ranked ambiguous candidates. */
    public AmbiguousLocatorException(String message) {
        this(message, null);
    }

    /** Creates an exception retaining structured ambiguity diagnostics. */
    public AmbiguousLocatorException(String message, LocatorDiagnostics diagnostics) {
        super(message);
        this.diagnostics = diagnostics;
    }

    /** Returns structured diagnostics when this exception was raised by the locator engine. */
    public java.util.Optional<LocatorDiagnostics> diagnostics() {
        return java.util.Optional.ofNullable(diagnostics);
    }
}
