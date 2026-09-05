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
    CONDITIONAL_STEP_INTERRUPTED,
    /**
     * A {@link WorkflowStepType#LOOP}'s continuation condition was still {@code true} after its
     * declared {@code maxIterations} bound was reached - added in 1.3.0. Fail-closed: reaching the
     * bound while continuation is still requested is a workflow failure, never silently treated as
     * a successful stop (see {@code docs/workflow.md#bounded-loops}).
     */
    LOOP_ITERATION_LIMIT_EXCEEDED,
    /**
     * A {@link WorkflowStepType#LOOP_ITERATION} observed the executing thread's interrupt flag set
     * at one of its evaluate/select boundaries - added in 1.3.0, mirroring {@link
     * #CONDITIONAL_STEP_INTERRUPTED} for loop iterations.
     */
    LOOP_STEP_INTERRUPTED,
    /**
     * Execution stopped because it would have exceeded this engine's cumulative executed-step-node
     * budget - added in 1.3.0. Guards against a combinatorially explosive but locally-valid
     * nested-loop structure (see {@code docs/workflow.md#bounded-loops}); never triggered by a
     * workflow with no {@link WorkflowStepType#LOOP} steps, since a purely sequential or
     * conditional definition's total executed-node count is already bounded by its own step count.
     */
    EXECUTED_NODE_BUDGET_EXCEEDED
}
