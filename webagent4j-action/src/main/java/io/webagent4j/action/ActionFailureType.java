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
    POLICY_VIOLATION,

    /**
     * An action policy authorized a specific, already-resolved target, but immediately before the
     * backend side effect this framework could not prove the target was still that exact same
     * concrete element - it was detached, replaced by another element satisfying the same semantic
     * locator, or its identity could not otherwise be confirmed. Fails closed exactly like {@link
     * #POLICY_DENIED}: the backend is never invoked, and {@link ActionExecutionMode#NOT_EXECUTED}
     * is reported. Distinct from {@link #POLICY_DENIED} because the policy itself may have allowed
     * the original target; the authorization was never transferable to a different one.
     */
    TARGET_CHANGED
}
