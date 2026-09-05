package io.webagent4j.workflow;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * One node of a {@link WorkflowExecutionPlan}: a purely structural, backend-neutral description of
 * one {@link IWorkflowStep} as declared in the workflow's definition - never what an execution
 * actually did (see {@link WorkflowExecutionNode} for that) and never a prediction of what a
 * runtime-dependent step will do.
 *
 * @param stepId the step's identifier
 * @param stepType the step's broad category
 * @param guarded whether this step carries an optional {@link IWorkflowStep#when} guard - meaning
 *     it is conditionally executable, never that it will or will not run; the guard itself is never
 *     evaluated to produce this flag. Always {@code false} for a {@link
 *     WorkflowStepType#CONDITIONAL} step, whose condition is a mandatory branch selector, not an
 *     optional guard.
 * @param declaredOutput the variable this step declares it publishes on success, if any - metadata
 *     only, never a value, and never present for a {@link WorkflowStepType#CONDITIONAL} step
 * @param branches this step's structurally possible branches. For a {@link
 *     WorkflowStepType#CONDITIONAL} step: always exactly two, {@link WorkflowBranchSelection#THEN}
 *     first and then either {@link WorkflowBranchSelection#ELSE} or {@link
 *     WorkflowBranchSelection#NONE} - both potential paths represented, never only the one a
 *     runtime decision would select. For a {@link WorkflowStepType#LOOP} step: always exactly one,
 *     of kind {@link WorkflowBranchSelection#THEN}, representing the loop's {@code body} - present
 *     once, structurally, never unrolled into {@code maxIterations} copies (see {@link
 *     WorkflowPlanner#plan}). Empty for every other step type.
 */
public record WorkflowPlanNode(
        WorkflowStepId stepId,
        WorkflowStepType stepType,
        boolean guarded,
        Optional<WorkflowPlanOutput> declaredOutput,
        List<WorkflowPlanBranch> branches) {

    /** Validates node shape invariants. */
    public WorkflowPlanNode {
        Objects.requireNonNull(stepId, "stepId");
        Objects.requireNonNull(stepType, "stepType");
        declaredOutput = Objects.requireNonNull(declaredOutput, "declaredOutput");
        branches = List.copyOf(Objects.requireNonNull(branches, "branches"));
        if (stepType == WorkflowStepType.CONDITIONAL) {
            if (guarded) {
                throw new IllegalArgumentException(
                        "a CONDITIONAL plan node cannot be guarded - its condition is a mandatory"
                                + " branch selector, not an optional when(...) guard");
            }
            if (declaredOutput.isPresent()) {
                throw new IllegalArgumentException(
                        "a CONDITIONAL plan node cannot declare an output");
            }
            if (branches.size() != 2) {
                throw new IllegalArgumentException(
                        "a CONDITIONAL plan node must carry exactly two branches (THEN and"
                                + " ELSE/NONE)");
            }
            if (branches.get(0).kind() != WorkflowBranchSelection.THEN) {
                throw new IllegalArgumentException(
                        "a CONDITIONAL plan node's first branch must be THEN");
            }
            WorkflowBranchSelection second = branches.get(1).kind();
            if (second != WorkflowBranchSelection.ELSE && second != WorkflowBranchSelection.NONE) {
                throw new IllegalArgumentException(
                        "a CONDITIONAL plan node's second branch must be ELSE or NONE");
            }
        } else if (stepType == WorkflowStepType.LOOP) {
            if (guarded) {
                throw new IllegalArgumentException(
                        "a LOOP plan node cannot be guarded - its condition is a mandatory"
                                + " continuation check, not an optional when(...) guard");
            }
            if (declaredOutput.isPresent()) {
                throw new IllegalArgumentException("a LOOP plan node cannot declare an output");
            }
            if (branches.size() != 1 || branches.get(0).kind() != WorkflowBranchSelection.THEN) {
                throw new IllegalArgumentException(
                        "a LOOP plan node must carry exactly one THEN branch, representing its"
                                + " body");
            }
        } else if (!branches.isEmpty()) {
            throw new IllegalArgumentException(
                    "only a CONDITIONAL or LOOP plan node may carry branches");
        }
    }
}
