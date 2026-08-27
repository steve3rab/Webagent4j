package io.webagent4j.action.policy;

/**
 * The context under which an {@link IActionPolicy} is being asked to evaluate one action.
 * Governed-execution policies may use this to distinguish an informational snapshot from an
 * authorization that is actually about to gate a backend call, but every mode still yields exactly
 * one {@link io.webagent4j.policy.PolicyOutcome}.
 */
public enum ActionPolicyMode {

    /**
     * A non-authoritative snapshot evaluation performed while building an {@code IActionPlan} (see
     * {@code IPreparedAction#plan()}). This evaluation never gates anything by itself - a {@code
     * DENY} here does not prevent {@code IActionPlan#execute()} from running, and {@code
     * IActionPlan#execute()} always re-evaluates the policy fresh in {@link #EXECUTE} mode before
     * any backend call. The snapshot exists purely for inspection via {@code
     * IActionPlan#policyDecisions()}.
     */
    PLAN,

    /**
     * The action is being validated as a dry run: the backend will not be invoked regardless of
     * this decision, but a {@code DENY} still causes the dry run itself to report failure, so a
     * caller can rehearse governance without ever risking a real side effect.
     */
    DRY_RUN,

    /**
     * The action is about to be executed for real. A {@code DENY} (or any evaluation failure)
     * prevents the backend from ever being invoked.
     */
    EXECUTE
}
