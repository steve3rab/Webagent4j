package io.webagent4j.observation;

import java.util.Objects;

/**
 * Base unchecked failure for semantic observation capture or transformation. Subclassing is
 * supported for domain-specific observation failures that preserve the safe-message contract.
 */
public class ObservationException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** Creates a failure with a safe message. */
    public ObservationException(String message) {
        super(Objects.requireNonNull(message, "message"));
    }

    /** Creates a failure with a safe message and backend cause. */
    public ObservationException(String message, Throwable cause) {
        super(Objects.requireNonNull(message, "message"), cause);
    }
}
