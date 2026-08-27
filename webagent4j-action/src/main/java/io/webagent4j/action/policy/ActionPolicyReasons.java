package io.webagent4j.action.policy;

import io.webagent4j.policy.PolicyReason;

/** Stable reason codes the framework itself uses for action-policy evaluation outcomes. */
public final class ActionPolicyReasons {

    /** An {@code IActionPolicy} threw, or returned a malformed {@code null} decision. */
    public static final PolicyReason EVALUATION_FAILED =
            PolicyReason.of("action.policy.evaluation-failed");

    private ActionPolicyReasons() {}
}
