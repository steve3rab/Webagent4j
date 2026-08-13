package io.webagent4j.common;

/** Base unchecked exception for failures that prevent WebAgent4J from honoring an API contract. */
public class WebAgentException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** Creates an exception with a diagnostic message. */
    public WebAgentException(String message) {
        super(message);
    }

    /** Creates an exception with a diagnostic message and its underlying cause. */
    public WebAgentException(String message, Throwable cause) {
        super(message, cause);
    }
}
