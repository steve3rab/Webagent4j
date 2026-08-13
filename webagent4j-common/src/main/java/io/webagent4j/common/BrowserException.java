package io.webagent4j.common;

/** Indicates an exceptional browser lifecycle or backend failure. */
public final class BrowserException extends WebAgentException {

    private static final long serialVersionUID = 1L;

    /** Creates a browser exception with a diagnostic message. */
    public BrowserException(String message) {
        super(message);
    }

    /** Creates a browser exception with a diagnostic message and cause. */
    public BrowserException(String message, Throwable cause) {
        super(message, cause);
    }
}
