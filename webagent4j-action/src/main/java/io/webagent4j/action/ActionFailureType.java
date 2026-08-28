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
    DOWNLOAD_FAILURE,

    /** An {@code IActionPolicy} or {@code INetworkPolicy} evaluated to {@code DENY}. */
    POLICY_DENIED,

    /**
     * Policy evaluation itself failed - threw, or returned a malformed/{@code null} decision -
     * before the backend was ever invoked. Fails closed: treated identically to {@link
     * #POLICY_DENIED} for the purpose of whether the backend action proceeds.
     */
    POLICY_EVALUATION_FAILED,

    /**
     * A backend side effect already happened ({@link ActionExecutionMode#REAL}) but a governed
     * post-execution check - currently only the final-URL network-policy check after a {@code
     * NAVIGATE} action - determined it should not have. Never paired with {@link
     * ActionExecutionMode#NOT_EXECUTED}: once a side effect may have happened, it is never reported
     * as not executed.
     */
    POLICY_VIOLATION
}
