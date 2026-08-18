package io.webagent4j.action;

/** Whether a prepared {@link IActionPlan} resolved cleanly and is safe to execute. */
public enum ActionPlanStatus {
    /** Target resolution and every precondition succeeded at the time the plan was built. */
    READY,
    /** Resolution failed, the target was ambiguous, or a precondition was not satisfied. */
    BLOCKED
}
