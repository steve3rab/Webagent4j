package io.webagent4j.common;

import java.util.Objects;

/**
 * Base unchecked exception for failures that prevent WebAgent4J from honoring an API contract.
 * Subclassing is supported for domain-specific failures that preserve the safe-message contract.
 */
public class WebAgentException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** Creates an exception with a diagnostic message. */
    public WebAgentException(String message) {
        super(Objects.requireNonNull(message, "message"));
    }

    /** Creates an exception with a diagnostic message and its underlying cause. */
    public WebAgentException(String message, Throwable cause) {
        super(Objects.requireNonNull(message, "message"), cause);
    }
}
