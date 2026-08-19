package io.webagent4j.common;

/** Indicates that an element could not be resolved under a locator's deterministic contract. */
public class LocatorException extends WebAgentException {

    private static final long serialVersionUID = 1L;

    /** Creates a locator exception with a diagnostic message. */
    public LocatorException(String message) {
        super(message);
    }

    /** Creates a locator exception with a diagnostic message and its underlying cause. */
    public LocatorException(String message, Throwable cause) {
        super(message, cause);
    }
}
