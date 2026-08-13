package io.webagent4j.locator;

import io.webagent4j.common.LocatorException;

/** Indicates that no compatible candidate appeared before the locator timeout. */
public final class LocatorNotFoundException extends LocatorException {

    private static final long serialVersionUID = 1L;

    private final transient LocatorDiagnostics diagnostics;

    /** Creates an exception containing a rendered locator diagnostic. */
    public LocatorNotFoundException(String message) {
        this(message, null);
    }

    /** Creates an exception retaining structured locator diagnostics. */
    public LocatorNotFoundException(String message, LocatorDiagnostics diagnostics) {
        super(message);
        this.diagnostics = diagnostics;
    }

    /** Returns structured diagnostics when this exception was raised by the locator engine. */
    public java.util.Optional<LocatorDiagnostics> diagnostics() {
        return java.util.Optional.ofNullable(diagnostics);
    }
}
