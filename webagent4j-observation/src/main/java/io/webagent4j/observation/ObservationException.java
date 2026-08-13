package io.webagent4j.observation;

/** Base unchecked failure for semantic observation capture or transformation. */
public class ObservationException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** Creates a failure with a safe message. */
    public ObservationException(String message) {
        super(message);
    }

    /** Creates a failure with a safe message and backend cause. */
    public ObservationException(String message, Throwable cause) {
        super(message, cause);
    }
}
