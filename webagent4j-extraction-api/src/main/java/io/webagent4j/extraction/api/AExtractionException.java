package io.webagent4j.extraction.api;

import java.util.Objects;

/**
 * Base type for extraction-specific failures.
 *
 * <p>Only failures with no existing typed representation live under this hierarchy: an absent or
 * ambiguous extraction source is already reported by the locator layer's own {@code
 * LocatorNotFoundException}/{@code AmbiguousLocatorException}, and a genuine backend or runtime
 * failure already propagates as itself. Neither is reinterpreted or wrapped here - only a missing
 * attribute, a failed conversion, or a failed validation are extraction's own concern. Subclassing
 * is supported for domain-specific failures that preserve this package's safe-message contract.
 */
public abstract class AExtractionException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** Creates an extraction failure with a descriptive message. */
    protected AExtractionException(String message) {
        super(Objects.requireNonNull(message, "message"));
    }

    /** Creates an extraction failure with a descriptive message and cause. */
    protected AExtractionException(String message, Throwable cause) {
        super(Objects.requireNonNull(message, "message"), cause);
    }
}
