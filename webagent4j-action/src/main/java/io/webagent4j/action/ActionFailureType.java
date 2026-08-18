package io.webagent4j.action;

/** Stable categories for expected action failures. */
public enum ActionFailureType {
    TARGET_NOT_FOUND,
    TARGET_AMBIGUOUS,
    PRECONDITION_FAILED,
    TARGET_NOT_INTERACTABLE,
    ACTION_NOT_SUPPORTED_BY_TARGET,
    BACKEND_FAILURE,
    TIMEOUT,
    POSTCONDITION_FAILED,
    INTERRUPTED,
    UPLOAD_FAILURE,
    DOWNLOAD_FAILURE
}
