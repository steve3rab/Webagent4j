package io.webagent4j.action;

/** Whether a prepared {@link IActionPlan} resolved cleanly and is safe to execute. */
public enum ActionPlanStatus {
    /**
     * Target resolution and every precondition succeeded, and every configured policy's snapshot
     * evaluation ({@link IActionPlan#policyDecisions()}) allowed this action, at the time the plan
     * was built.
     */
    READY,
    /**
     * Resolution failed or was interrupted, the target was ambiguous, a precondition failed, or a
     * configured policy's snapshot evaluation denied this action or itself failed to evaluate.
     */
    BLOCKED
}
