package io.webagent4j.action;

/**
 * Ordered audit stages emitted by an action pipeline.
 *
 * <p><strong>Ordinal compatibility:</strong> every constant present in {@code 1.0.0} - {@link
 * #ACTION_STARTED} through {@link #ACTION_FAILED} below - must keep its exact {@code 1.0.0} ordinal
 * forever; a caller may depend on {@link Enum#ordinal()} or on serialized ordinal values. Any new
 * constant added in a later release must be appended after {@link #ACTION_FAILED} (or after the
 * newest existing constant), never inserted in the middle - see {@code ActionStageOrdinalTest},
 * which locks the exact {@code 1.0.0} name-to-ordinal mapping and fails if this ordering ever
 * regresses.
 */
public enum ActionStage {
    ACTION_STARTED,
    TARGET_RESOLUTION_STARTED,
    TARGET_RESOLVED,
    PRECONDITION_STARTED,
    PRECONDITION_COMPLETED,
    BACKEND_ACTION_STARTED,
    BACKEND_ACTION_COMPLETED,
    STABILIZATION_STARTED,
    STABILIZATION_COMPLETED,
    VERIFICATION_STARTED,
    VERIFICATION_COMPLETED,
    ACTION_COMPLETED,
    ACTION_FAILED,

    /**
     * A governed-execution policy (action or network) is about to be evaluated. Added in {@code
     * 1.1.0}; appended after every {@code 1.0.0} constant so none of their ordinals changed.
     */
    POLICY_EVALUATION_STARTED,

    /**
     * A governed-execution policy evaluation finished - see the event's {@code metadata} for {@code
     * policy.kind}, {@code policy.phase}, {@code policy.outcome}, and {@code policy.reason}, the
     * safe structured data {@link ActionResult#decisionTrace()} is derived from. Added in {@code
     * 1.1.0}; appended after every {@code 1.0.0} constant so none of their ordinals changed.
     */
    POLICY_EVALUATION_COMPLETED
}
