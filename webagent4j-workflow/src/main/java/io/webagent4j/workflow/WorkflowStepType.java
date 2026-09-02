package io.webagent4j.workflow;

/** Broad category of one {@link IWorkflowStep}, for safe diagnostics. */
public enum WorkflowStepType {
    /** A step backed by an {@link IWorkflowActionFactory} and the action pipeline. */
    ACTION,
    /** A step that assigns a literal public value to a variable. */
    ASSIGN,
    /**
     * A step that evaluates a condition exactly once and executes exactly one of two branches -
     * added in 1.2.0, see {@link WorkflowSteps#ifElse} and {@link WorkflowSteps#ifThen}.
     */
    CONDITIONAL
}
