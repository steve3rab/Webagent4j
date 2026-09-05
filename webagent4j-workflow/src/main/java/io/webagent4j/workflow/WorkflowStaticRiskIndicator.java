package io.webagent4j.workflow;

/**
 * A single, purely structural fact about a {@link Workflow} definition surfaced by {@link
 * WorkflowIntrospector#inspect(Workflow)} - never a vulnerability, a score, or a policy verdict.
 *
 * <p>Each constant names one static characteristic that a caller's own policy might care about;
 * {@link WorkflowIntrospector} never decides whether any of these is acceptable, and their presence
 * in a {@link WorkflowIntrospectionReport} never means the definition is unsafe or invalid - a
 * workflow that legitimately uses loops, parallelism, actions, or secrets is not thereby suspect.
 * {@link WorkflowIntrospectionReport#riskIndicators()} lists at most one instance of each constant
 * that applies, always in this enum's declaration order, so two reports for logically equal
 * workflows always list them identically.
 */
public enum WorkflowStaticRiskIndicator {

    /**
     * {@link WorkflowIntrospectionReport#maximumPotentialExecutionNodes()} exceeds this engine's
     * cumulative executed-step-node budget, or that computation saturated before a definitive
     * comparison could be made - see {@link
     * WorkflowIntrospectionReport#mayExceedRuntimeNodeBudget()}. This is information a caller's own
     * policy may act on, never a validation failure: a workflow whose declared bounds could in
     * principle exceed the runtime budget may still terminate long before doing so on every real
     * execution (see {@code docs/workflow.md#static-workflow-introspection}).
     */
    MAY_EXCEED_RUNTIME_NODE_BUDGET,

    /** This workflow declares at least one {@link WorkflowStepType#LOOP} step, at any depth. */
    CONTAINS_LOOPS,

    /** This workflow declares at least one {@link WorkflowStepType#PARALLEL} step, at any depth. */
    CONTAINS_PARALLELISM,

    /** This workflow declares at least one {@link WorkflowStepType#ACTION} step, at any depth. */
    CONTAINS_ACTIONS,

    /**
     * This workflow declares at least one secret <b>output</b> - see {@link
     * WorkflowIntrospectionReport#secretOutputCount()} and {@link WorkflowVariable#secret()}. A
     * secret <b>input</b> alone (see {@link WorkflowIntrospectionReport#containsSecrets()}, which
     * covers both) does not by itself set this indicator. Never accompanied by a secret value: see
     * {@link WorkflowIntrospectionReport}'s class-level secret-safety note.
     */
    CONTAINS_SECRET_OUTPUTS
}
