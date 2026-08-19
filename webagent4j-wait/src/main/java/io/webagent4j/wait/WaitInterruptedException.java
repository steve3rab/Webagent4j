package io.webagent4j.wait;

import io.webagent4j.common.WebAgentException;

/**
 * Signals that a wait was stopped because the current thread was interrupted, either before or
 * during a sleep between polls.
 *
 * <p>The interrupt status of the current thread is always restored before this exception is thrown,
 * so a caller that catches it can still observe {@link Thread#isInterrupted()}. Domain adapters
 * (locator, verification, action) are free to translate this into their own historical exception
 * type to preserve an existing public contract.
 */
public final class WaitInterruptedException extends WebAgentException {

    private static final long serialVersionUID = 1L;

    /** Creates an interruption failure with a diagnostic message. */
    public WaitInterruptedException(String message) {
        super(message);
    }
}
