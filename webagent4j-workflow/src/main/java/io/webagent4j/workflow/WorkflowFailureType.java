package io.webagent4j.workflow;

/** Stable categories for a workflow-level failure. */
public enum WorkflowFailureType {
    /** A declared required input was not supplied. */
    MISSING_REQUIRED_INPUT,
    /** A supplied input's runtime value did not match its declared type. */
    INPUT_TYPE_MISMATCH,
    /** An executing step or its condition referenced a variable that has no value. */
    MISSING_VARIABLE,
    /** A condition's evaluation itself failed (distinct from evaluating to {@code false}). */
    CONDITION_EVALUATION_FAILED,
    /** An {@link IWorkflowActionFactory} threw while preparing an action. */
    ACTION_FACTORY_FAILED,
    /** A prepared action executed and its {@code ActionResult} reported failure. */
    ACTION_FAILED,
    /** A non-action step failed for a reason specific to that step. */
    STEP_FAILED,
    /** A step threw an unexpected {@link RuntimeException}. */
    STEP_EXCEPTION,
    /** A step's produced value did not match its declared output variable's type. */
    OUTPUT_TYPE_MISMATCH,
    /** A step declared an output variable but produced no value. */
    NULL_OUTPUT
}
