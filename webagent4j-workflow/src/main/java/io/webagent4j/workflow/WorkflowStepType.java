package io.webagent4j.workflow;

/** Broad category of one {@link IWorkflowStep}, for safe diagnostics. */
public enum WorkflowStepType {
    /** A step backed by an {@link IWorkflowActionFactory} and the action pipeline. */
    ACTION,
    /** A step that assigns a literal public value to a variable. */
    ASSIGN
}
