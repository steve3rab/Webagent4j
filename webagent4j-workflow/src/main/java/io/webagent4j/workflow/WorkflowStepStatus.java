package io.webagent4j.workflow;

/** Terminal outcome of one {@link IWorkflowStep} within a workflow execution. */
public enum WorkflowStepStatus {
    /** The step executed and succeeded. */
    SUCCEEDED,
    /** The step's condition evaluated to {@code false}; the step never executed. */
    SKIPPED,
    /** The step, or its condition's evaluation, failed. */
    FAILED,
    /** The workflow already failed at an earlier step; this step never ran. */
    NOT_RUN
}
