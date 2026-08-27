package io.webagent4j.action;

/** Ordered audit stages emitted by an action pipeline. */
public enum ActionStage {
    ACTION_STARTED,
    TARGET_RESOLUTION_STARTED,
    TARGET_RESOLVED,
    PRECONDITION_STARTED,
    PRECONDITION_COMPLETED,
    /** A governed-execution policy (action or network) is about to be evaluated. */
    POLICY_EVALUATION_STARTED,

    /**
     * A governed-execution policy evaluation finished - see the event's {@code metadata} for {@code
     * policy.kind}, {@code policy.phase}, {@code policy.outcome}, and {@code policy.reason}, the
     * safe structured data {@link ActionResult#decisionTrace()} is derived from.
     */
    POLICY_EVALUATION_COMPLETED,

    BACKEND_ACTION_STARTED,
    BACKEND_ACTION_COMPLETED,
    STABILIZATION_STARTED,
    STABILIZATION_COMPLETED,
    VERIFICATION_STARTED,
    VERIFICATION_COMPLETED,
    ACTION_COMPLETED,
    ACTION_FAILED
}
