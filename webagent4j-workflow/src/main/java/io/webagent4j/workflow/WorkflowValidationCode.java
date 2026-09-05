package io.webagent4j.workflow;

/**
 * Stable category for one {@link WorkflowValidationDiagnostic}, one per structural invariant {@link
 * Workflow.Builder#build()} enforces - see {@code docs/workflow.md#validation-report}.
 */
public enum WorkflowValidationCode {
    /** The definition declares no steps at all. */
    EMPTY_STEP_LIST,
    /** A required or optional input name was declared more than once. */
    DUPLICATE_INPUT_DECLARATION,
    /** Two steps - at any nesting depth, in either branch - declared the same step ID. */
    DUPLICATE_STEP_ID,
    /** A conditional step is nested deeper than {@link Workflow#MAX_CONDITIONAL_NESTING_DEPTH}. */
    CONDITIONAL_DEPTH_EXCEEDED,
    /**
     * A custom {@link IWorkflowCondition}'s {@link IWorkflowCondition#referencedVariables()} threw,
     * returned {@code null}, or contained a {@code null} entry.
     */
    CONDITION_METADATA_INVALID,
    /**
     * A step's guard, or a conditional's mandatory branch selector, referenced a variable that is
     * not a declared input or an earlier step's definitely-published output - either because it was
     * never declared at all, or because it is declared but only guarded, or only produced by one
     * branch of an earlier conditional (see {@code docs/workflow.md#branching} for guard-aware
     * definite assignment).
     */
    OUTPUT_NOT_DEFINITELY_AVAILABLE,
    /** A step's declared output structurally collides with an existing input or earlier output. */
    OUTPUT_COLLISION,
    /** Two producers of the same output name declared it with different runtime types. */
    OUTPUT_TYPE_MISMATCH,
    /** Two producers of the same output name disagreed on whether it is secret. */
    OUTPUT_SECRET_CLASSIFICATION_MISMATCH,
    /**
     * A {@link WorkflowStepType#LOOP}'s declared {@code maxIterations} is not a positive integer,
     * or exceeds {@link Workflow#MAX_LOOP_ITERATIONS} - added in 1.3.0.
     */
    LOOP_INVALID_MAX_ITERATIONS,
    /**
     * A {@link WorkflowStepType#LOOP} or {@link WorkflowStepType#CONDITIONAL} step is nested deeper
     * than {@link Workflow#MAX_CONTROL_FLOW_NESTING_DEPTH} - added in 1.3.0, generalizing {@link
     * #CONDITIONAL_DEPTH_EXCEEDED} (kept unchanged for a {@code CONDITIONAL} step that is itself
     * the one exceeding the shared bound) to a {@code LOOP} step exceeding that same shared bound.
     */
    LOOP_NESTING_DEPTH_EXCEEDED
}
