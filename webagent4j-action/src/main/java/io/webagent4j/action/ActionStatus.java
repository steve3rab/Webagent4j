package io.webagent4j.action;

/** Final state of an action pipeline. */
public enum ActionStatus {
    SUCCESS,
    PRECONDITION_FAILED,
    EXECUTION_FAILED,
    VERIFICATION_FAILED,
    TIMEOUT,
    CANCELLED
}
