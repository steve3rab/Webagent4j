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
    CONDITIONAL,
    /**
     * A bounded, deterministic repetition of a body over at most a declared {@code maxIterations} -
     * added in 1.3.0, see {@link WorkflowSteps#loop}. Appears once in a {@link Workflow} definition
     * and in a {@link WorkflowExecutionPlan} (never unrolled); an actual execution instead produces
     * one {@link WorkflowStepType#LOOP_ITERATION} entry per continuation check it performed - see
     * that type's Javadoc.
     */
    LOOP,
    /**
     * A single continuation-check-and-body-run performed by a {@link WorkflowStepType#LOOP} during
     * one execution - added in 1.3.0. Never appears in a {@link Workflow} definition or a {@link
     * WorkflowExecutionPlan}: it exists only in a {@link WorkflowExecutionTree}/{@code
     * WorkflowResult#steps()} and its Recording V2 counterpart, one per iteration the loop actually
     * attempted (including the final, never-started check that discovers the bound was reached
     * while the condition was still true). Structurally identical in shape to an {@code ifThen}
     * decision: {@link WorkflowStepStatus#SUCCEEDED} with the condition's outcome and, when {@code
     * true}, the iteration's own body steps as children ({@link WorkflowBranchSelection#THEN});
     * {@code false} is a no-op with no children ({@link WorkflowBranchSelection#NONE}), exactly
     * like {@code ifThen}'s own false decision - see {@code docs/workflow.md#bounded-loops}.
     */
    LOOP_ITERATION,

    /**
     * A bounded set of explicitly declared, read-only/observational branches, evaluated
     * concurrently and joined in deterministic, branch-definition order - added in 1.3.0, see
     * {@link WorkflowSteps#parallel}. Appears once in a {@link Workflow} definition and in a {@link
     * WorkflowExecutionPlan}; an actual execution produces one {@link
     * WorkflowStepType#PARALLEL_BRANCH} entry per declared branch, always in definition order,
     * regardless of the real order in which branches actually finished (see {@code
     * docs/workflow.md#parallel}).
     */
    PARALLEL,

    /**
     * One declared branch of a {@link WorkflowStepType#PARALLEL} step, as actually executed - added
     * in 1.3.0. Never appears in a {@link Workflow} definition or a {@link WorkflowExecutionPlan}:
     * it exists only in a {@link WorkflowExecutionTree}/{@code WorkflowResult#steps()} and its
     * Recording V2 counterpart, exactly one per branch the enclosing {@code PARALLEL} step
     * declares, always in that branch's own definition order - never in the order branches actually
     * finished. {@link WorkflowStepStatus#SUCCEEDED} means this branch was launched and its own
     * declared steps were attempted (as its children, in order - the branch's own outcome, success
     * or failure, is carried by those children exactly like a loop iteration's body); {@link
     * WorkflowStepStatus#NOT_RUN} means this branch was never launched at all - either because an
     * earlier-declared sibling branch's failure made the overall {@code PARALLEL} step fail before
     * this branch's own outcome could be authoritatively included, or because the whole {@code
     * PARALLEL} step never started - and carries zero children, exactly like any other never-run
     * step (see {@code docs/workflow.md#parallel}).
     */
    PARALLEL_BRANCH
}
