package io.webagent4j.workflow;

/**
 * Overall terminal outcome of one {@link WorkflowEngine#execute(Workflow, WorkflowInputs)} call.
 */
public enum WorkflowStatus {
    /** Every executable step succeeded or was skipped; no step failed. */
    COMPLETED,
    /** Execution stopped at the first failed step (or before step 0, for invalid inputs). */
    FAILED
}
