package io.webagent4j.verification;

/** Signals that deterministic verification polling respected thread interruption. */
public final class VerificationInterruptedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** Creates an interruption failure without an underlying checked exception. */
    public VerificationInterruptedException(String message) {
        super(message);
    }

    /** Creates an interruption failure preserving the underlying checked exception. */
    public VerificationInterruptedException(String message, Throwable cause) {
        super(message, cause);
    }
}
