package io.webagent4j.observation;

/** Failure raised when the global observation deadline is exceeded. */
public final class ObservationTimeoutException extends ObservationException {

    private static final long serialVersionUID = 1L;

    /** Creates a timeout failure. */
    public ObservationTimeoutException(String message) {
        super(message);
    }
}
