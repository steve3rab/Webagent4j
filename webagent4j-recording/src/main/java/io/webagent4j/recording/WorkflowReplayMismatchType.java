package io.webagent4j.recording;

/** Stable category of one difference found by {@link WorkflowReplayVerifier}. */
public enum WorkflowReplayMismatchType {
    /** The actual result's workflow identifier differs from the recording's. */
    WORKFLOW_ID_MISMATCH,
    /** The actual result's overall status differs from the recording's. */
    WORKFLOW_STATUS_MISMATCH,
    /** The actual result has a different number of steps than the recording. */
    STEP_COUNT_MISMATCH,
    /** A step at the same index has a different step identifier. */
    STEP_ID_MISMATCH,
    /** A step at the same index has a different step type. */
    STEP_TYPE_MISMATCH,
    /** A step at the same index has a different terminal status. */
    STEP_STATUS_MISMATCH,
    /** A step's recorded condition presence differs from the actual result's. */
    CONDITION_PRESENCE_MISMATCH,
    /** A step's condition outcome differs. */
    CONDITION_OUTCOME_MISMATCH,
    /** A step's output-variable presence or name differs. */
    OUTPUT_VARIABLE_MISMATCH,
    /** A step's recorded action presence differs from the actual result's. */
    ACTION_PRESENCE_MISMATCH,
    /** A step's action type differs. */
    ACTION_TYPE_MISMATCH,
    /** A step's action status differs. */
    ACTION_STATUS_MISMATCH,
    /** A step's action execution mode differs. */
    ACTION_EXECUTION_MODE_MISMATCH,
    /** A failure's presence differs between the recording and the actual result. */
    FAILURE_PRESENCE_MISMATCH,
    /** A failure's type differs. */
    FAILURE_TYPE_MISMATCH,
    /** A failure's associated step identifier differs. */
    FAILURE_STEP_ID_MISMATCH,
    /** A failure's action failure type differs. */
    ACTION_FAILURE_TYPE_MISMATCH,
    /** The recording has a step, at some tail index, that the actual result does not have. */
    MISSING_STEP,
    /** The actual result has a step, at some tail index, that the recording does not have. */
    EXTRA_STEP
}
