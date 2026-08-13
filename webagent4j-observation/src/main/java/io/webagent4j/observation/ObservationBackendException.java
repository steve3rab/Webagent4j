package io.webagent4j.observation;

/** Failure raised when a browser adapter cannot produce a backend-neutral snapshot. */
public final class ObservationBackendException extends ObservationException {

    private static final long serialVersionUID = 1L;

    /** Creates a backend failure without exposing captured values. */
    public ObservationBackendException(String message, Throwable cause) {
        super(message, cause);
    }
}
