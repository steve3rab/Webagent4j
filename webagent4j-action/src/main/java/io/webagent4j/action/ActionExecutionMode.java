package io.webagent4j.action;

/** Indicates whether an action was actually executed or only simulated. */
public enum ActionExecutionMode {
    REAL,
    DRY_RUN,
    NOT_EXECUTED
}
