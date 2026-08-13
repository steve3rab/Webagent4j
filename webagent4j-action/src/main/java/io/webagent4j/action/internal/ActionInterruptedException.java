package io.webagent4j.action.internal;

/** Internal signal preserving thread interruption across action stages. */
final class ActionInterruptedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    ActionInterruptedException(Throwable cause) {
        super("Action execution was interrupted", cause);
    }
}
