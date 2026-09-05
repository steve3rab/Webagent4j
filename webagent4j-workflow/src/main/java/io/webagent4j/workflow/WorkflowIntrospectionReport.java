package io.webagent4j.workflow;

import java.util.List;
import java.util.Objects;

/**
 * Immutable, deterministic, backend-neutral static-complexity and safety-surface summary of a
 * {@link Workflow} definition, produced by {@link WorkflowIntrospector#inspect(Workflow)} without
 * ever executing anything - see {@code docs/workflow.md#static-workflow-introspection}.
 *
 * <p>This is a structural sibling to {@link WorkflowValidationReport}, {@link
 * WorkflowExecutionPlan}, and {@link WorkflowExecutionTree}, not a replacement for any of them:
 *
 * <ul>
 *   <li>{@link WorkflowValidationReport} answers <em>is this definition structurally coherent, and
 *       why (or why not)?</em>
 *   <li>{@link WorkflowExecutionPlan} answers <em>what could structurally execute?</em>
 *   <li>This report answers <em>how complex is that structure, and what bounded runtime pressure
 *       may it represent?</em>
 *   <li>{@link WorkflowExecutionTree} answers <em>what actually executed?</em>
 * </ul>
 *
 * <p><b>Secret safety:</b> this report is safe to log. It contains metadata only - names, declared
 * runtime types, counts, and booleans - and never a workflow input or output <em>value</em>, secret
 * or otherwise.
 *
 * <p><b>No score:</b> every field here is a measurable, individually documented fact. There is no
 * combined "risk score" or "complexity score" - see {@link #riskIndicators()} for the closest
 * thing, a small set of named structural facts a caller's own policy may act on.
 *
 * <p><b>Upper bounds, not predictions:</b> {@link #maximumPotentialExecutionNodes()} and every
 * field derived from it describe what this workflow's <em>declared structural bounds</em> permit in
 * the worst case - never a prediction of what a particular execution will actually do. A condition
 * is never evaluated to compute this report, so a loop that always exits after its first iteration
 * in practice is indistinguishable, here, from one that always runs to its declared {@code
 * maxIterations} bound; both report the same worst-case potential. See {@code
 * docs/workflow.md#static-workflow-introspection} for the complete list of limitations.
 *
 * @param workflowId the inspected definition's identifier
 * @param definitionNodeCount the total number of {@link IWorkflowStep}s present in the definition,
 *     counted once each at every nesting depth (a loop body's steps, a conditional branch's steps,
 *     and a parallel branch's steps are each counted exactly once, structurally - never unrolled or
 *     multiplied by any declared bound)
 * @param maximumControlFlowDepth the deepest combined {@link WorkflowStepType#CONDITIONAL}/{@link
 *     WorkflowStepType#LOOP}/{@link WorkflowStepType#PARALLEL} nesting level this definition
 *     actually reaches - the exact same counter and semantics as {@code
 *     Workflow#MAX_CONTROL_FLOW_NESTING_DEPTH} enforces at {@code Workflow.Builder#build()} time,
 *     never a second, divergent definition; always at most that bound, since this report can only
 *     be produced for an already-built, already-valid {@link Workflow}
 * @param conditionalCount the number of {@link WorkflowStepType#CONDITIONAL} steps, at any depth
 * @param loopCount the number of {@link WorkflowStepType#LOOP} steps, at any depth
 * @param parallelCount the number of {@link WorkflowStepType#PARALLEL} steps, at any depth
 * @param actionCount the number of {@link WorkflowStepType#ACTION} steps, at any depth
 * @param declaredInputCount {@code requiredInputCount + optionalInputCount}
 * @param requiredInputCount the number of declared required inputs
 * @param optionalInputCount the number of declared optional inputs
 * @param inputs every declared input's own metadata, in declaration order (required inputs, then
 *     optional inputs) - never a value
 * @param declaredOutputCount the number of distinct output variables declared by some step, at any
 *     depth (a variable published identically by more than one structurally exclusive branch is
 *     still counted once, since it is one logical variable - see {@link WorkflowVariable#equals})
 * @param definitelyAvailableOutputCount the number of declared outputs that are definitely
 *     available - see {@link WorkflowIntrospectionOutput#definitelyAvailable()}
 * @param secretOutputCount the number of declared outputs that are {@link
 *     WorkflowVariable#secret()}
 * @param outputs every declared output's own metadata, in definition-traversal order - never a
 *     value
 * @param maximumLoopIterations the largest {@code maxIterations} declared by any single {@link
 *     WorkflowStepType#LOOP} step in this definition, or {@code 0} if {@link #loopCount()} is zero
 *     - never a sum or product across multiple loops, which {@link
 *     #maximumPotentialExecutionNodes()} already accounts for
 * @param maximumParallelBranches the largest branch count declared by any single {@link
 *     WorkflowStepType#PARALLEL} step in this definition, or {@code 0} if {@link #parallelCount()}
 *     is zero
 * @param totalParallelBranches the sum, across every {@link WorkflowStepType#PARALLEL} step in this
 *     definition, of that step's own declared branch count - a purely structural definition-count,
 *     never multiplied by any enclosing loop's {@code maxIterations}
 * @param maximumPotentialExecutionNodes a conservative upper bound on the number of entries {@code
 *     WorkflowResult#steps()} could contain for any single valid execution of this workflow, given
 *     its declared structural bounds ({@code maxIterations} for every loop, the declared branch
 *     count for every parallel step, and the more expensive of a conditional's two branches) - see
 *     {@code docs/workflow.md#static-workflow-introspection} for the exact formula and why it is a
 *     worst case, never a prediction. Saturates at {@link Long#MAX_VALUE} rather than silently
 *     overflowing - see {@link #maximumPotentialExecutionNodesSaturated()}
 * @param maximumPotentialExecutionNodesSaturated whether {@link #maximumPotentialExecutionNodes()}
 *     reached {@link Long#MAX_VALUE} because the true worst-case count overflowed a {@code long},
 *     rather than because the true worst case happens to equal {@link Long#MAX_VALUE} exactly - a
 *     caller that needs to tell these two cases apart must check this flag, never compare the count
 *     itself to {@link Long#MAX_VALUE}
 * @param mayExceedRuntimeNodeBudget whether {@link #maximumPotentialExecutionNodes()} exceeds this
 *     engine's own cumulative executed-step-node budget, or whether that computation saturated
 *     before a definitive comparison could be made - see {@code
 *     docs/workflow.md#static-workflow-introspection}. This is information for a caller's own
 *     policy, never a validation failure: {@code true} does not mean this workflow is invalid or
 *     will fail, only that its declared structural bounds do not rule out eventually reaching that
 *     budget
 * @param containsActions whether {@link #actionCount()} is greater than zero
 * @param containsLoops whether {@link #loopCount()} is greater than zero
 * @param containsParallelism whether {@link #parallelCount()} is greater than zero
 * @param containsSecrets whether this definition declares at least one secret input or output
 * @param riskIndicators every {@link WorkflowStaticRiskIndicator} that applies to this definition,
 *     at most one instance of each, always in {@link WorkflowStaticRiskIndicator}'s own declaration
 *     order - never a score, never caller-influenced ordering
 */
public record WorkflowIntrospectionReport(
        WorkflowId workflowId,
        long definitionNodeCount,
        int maximumControlFlowDepth,
        long conditionalCount,
        long loopCount,
        long parallelCount,
        long actionCount,
        int declaredInputCount,
        int requiredInputCount,
        int optionalInputCount,
        List<WorkflowIntrospectionInput> inputs,
        int declaredOutputCount,
        int definitelyAvailableOutputCount,
        int secretOutputCount,
        List<WorkflowIntrospectionOutput> outputs,
        int maximumLoopIterations,
        int maximumParallelBranches,
        long totalParallelBranches,
        long maximumPotentialExecutionNodes,
        boolean maximumPotentialExecutionNodesSaturated,
        boolean mayExceedRuntimeNodeBudget,
        boolean containsActions,
        boolean containsLoops,
        boolean containsParallelism,
        boolean containsSecrets,
        List<WorkflowStaticRiskIndicator> riskIndicators) {

    /** Validates report invariants and defensively copies every collection. */
    public WorkflowIntrospectionReport {
        Objects.requireNonNull(workflowId, "workflowId");
        inputs = List.copyOf(Objects.requireNonNull(inputs, "inputs"));
        outputs = List.copyOf(Objects.requireNonNull(outputs, "outputs"));
        riskIndicators = List.copyOf(Objects.requireNonNull(riskIndicators, "riskIndicators"));
        if (definitionNodeCount < 0
                || maximumControlFlowDepth < 0
                || conditionalCount < 0
                || loopCount < 0
                || parallelCount < 0
                || actionCount < 0
                || declaredInputCount < 0
                || requiredInputCount < 0
                || optionalInputCount < 0
                || declaredOutputCount < 0
                || definitelyAvailableOutputCount < 0
                || secretOutputCount < 0
                || maximumLoopIterations < 0
                || maximumParallelBranches < 0
                || totalParallelBranches < 0
                || maximumPotentialExecutionNodes < 0) {
            throw new IllegalArgumentException("counts cannot be negative");
        }
        if (declaredInputCount != requiredInputCount + optionalInputCount) {
            throw new IllegalArgumentException(
                    "declaredInputCount must equal requiredInputCount + optionalInputCount");
        }
        if (definitelyAvailableOutputCount > declaredOutputCount) {
            throw new IllegalArgumentException(
                    "definitelyAvailableOutputCount cannot exceed declaredOutputCount");
        }
        if (secretOutputCount > declaredOutputCount) {
            throw new IllegalArgumentException(
                    "secretOutputCount cannot exceed declaredOutputCount");
        }
    }
}
