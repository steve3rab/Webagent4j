package io.webagent4j.action.policy;

import io.webagent4j.policy.PolicyReason;

/** Stable reason codes the framework itself uses for action-policy evaluation outcomes. */
public final class ActionPolicyReasons {

    /** An {@code IActionPolicy} threw, or returned a malformed {@code null} decision. */
    public static final PolicyReason EVALUATION_FAILED =
            PolicyReason.of("action.policy.evaluation-failed");

    /** Used by {@link ActionPolicies#allowAll()}. */
    public static final PolicyReason ALLOWED = PolicyReason.of("action.policy.allowed");

    /** Used by {@link ActionPolicies#denyAll()}. */
    public static final PolicyReason DENIED = PolicyReason.of("action.policy.denied");

    /** Used by {@link ActionPolicies#allowOnlyTypes} and {@link ActionPolicies#denyTypes}. */
    public static final PolicyReason ACTION_TYPE_DENIED = PolicyReason.of("action.type.denied");

    /**
     * Used by {@link ActionPolicies#allowOnlySideEffects} and {@link
     * ActionPolicies#denySideEffects}.
     */
    public static final PolicyReason SIDE_EFFECT_DENIED =
            PolicyReason.of("action.side-effect.denied");

    /** Used by {@link ActionPolicies#denyNonIdempotent()}. */
    public static final PolicyReason NON_IDEMPOTENT_DENIED =
            PolicyReason.of("action.non-idempotent.denied");

    private ActionPolicyReasons() {}
}
