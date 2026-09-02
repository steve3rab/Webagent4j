package io.webagent4j.workflow;

/** Stable categories for a workflow-level failure. */
public enum WorkflowFailureType {
    /** A declared required input was not supplied. */
    MISSING_REQUIRED_INPUT,
    /** A supplied input's variable declaration conflicted with the workflow's declared input. */
    INPUT_TYPE_MISMATCH,
    /** A supplied input's name matched neither a required nor an optional workflow input. */
    UNDECLARED_INPUT,
    /** An executing step or its condition referenced a variable that has no value. */
    MISSING_VARIABLE,
    /** A condition's evaluation, or its {@code describe()}, itself failed or was malformed. */
    CONDITION_EVALUATION_FAILED,
    /** An {@link IWorkflowActionFactory} threw while preparing an action. */
    ACTION_FACTORY_FAILED,
    /** A prepared action executed and its {@code ActionResult} reported failure. */
    ACTION_FAILED,
    /** A step threw an unexpected {@link RuntimeException}. */
    STEP_EXCEPTION,
    /** A step's produced value did not match its declared output variable's type. */
    OUTPUT_TYPE_MISMATCH,
    /** A step declared an output variable but produced no value. */
    NULL_OUTPUT,
    /**
     * A {@link WorkflowStepType#CONDITIONAL} step observed the executing thread's interrupt flag
     * set at one of its two evaluate/select boundaries - added in 1.2.0, see {@code
     * docs/workflow.md#branching}.
     */
    CONDITIONAL_STEP_INTERRUPTED
}
