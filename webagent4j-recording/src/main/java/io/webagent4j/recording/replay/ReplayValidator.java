package io.webagent4j.recording.replay;

import io.webagent4j.recording.RecordedExecutionNodeV2;
import io.webagent4j.recording.WorkflowRecordingV2;
import io.webagent4j.workflow.Workflow;
import io.webagent4j.workflow.WorkflowBranchSelection;
import io.webagent4j.workflow.WorkflowPlanBranch;
import io.webagent4j.workflow.WorkflowPlanNode;
import io.webagent4j.workflow.WorkflowPlanner;
import io.webagent4j.workflow.WorkflowStatus;
import io.webagent4j.workflow.WorkflowStepStatus;
import io.webagent4j.workflow.WorkflowStepType;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Validates that a {@link WorkflowRecordingV2} is eligible for Deterministic Replay against a live
 * {@link Workflow}, before any replay of its recorded trace begins.
 *
 * <p>{@code recording} is treated as fully untrusted input; {@code workflow} is treated as trusted,
 * live structure. {@link #validate} invokes nothing on {@code workflow} beyond {@link
 * WorkflowPlanner#plan(Workflow)} - it never evaluates a condition, never invokes an {@code
 * IWorkflowActionFactory}, and never performs any side effect. It is pure and stateless: the same
 * two arguments always produce the same result, and calling it never mutates either argument.
 *
 * <p><b>Replay eligibility rests on two independent guarantees, not one:</b> (1) {@code
 * recording.nodes()} is itself a structurally authorized path through {@code recording.plan()} -
 * the same step IDs, types, and declared outputs at the same positions, and every recorded branch
 * selection corresponding exclusively to that plan node's matching branch - and (2) {@code
 * recording.plan()} matches the live {@code workflow}'s current structure. The first is a
 * precondition this class relies on rather than re-derives: {@link WorkflowRecordingV2}'s own
 * compact constructor (see {@link io.webagent4j.recording.RecordingV2PlanTreeValidator}) already
 * guarantees it for every possible construction path, so no {@code WorkflowRecordingV2} instance
 * can exist whose tree is inconsistent with its own plan. {@link #validate} only checks the second:
 * {@code recording.plan()} (captured once, at record time) compared for exact equality against
 * {@code WorkflowPlanner.plan(workflow)} (recomputed fresh, from {@code workflow}'s current
 * definition). {@link WorkflowPlanner#plan} is deterministic and reads only a workflow's static
 * step structure, so this equality check is exactly "does {@code workflow}'s current step structure
 * - types, guards, declared outputs, and conditional branch shapes - still match what was
 * recorded," independent of whatever runtime input values a caller might supply. A structural
 * change to the workflow definition since capture (a step added, removed, retyped, or reordered)
 * always fails this check. Matching the recorded plan alone was never sufficient on its own - a
 * plan match says nothing about whether the recorded tree paired with it is genuine - which is
 * exactly why guarantee (1) exists as a separate, unconditionally-enforced precondition rather than
 * being folded into this equality check.
 *
 * <p><b>Why only {@code COMPLETED} is supported:</b> replaying a {@code FAILED} recording's trace
 * is out of scope for this initial structural/decision-replay implementation - see {@link
 * ReplayFailureType#UNSUPPORTED_STATUS}. This is a deliberate, documented scope decision, not an
 * oversight: a future revision may define what replaying a failure means, but this one does not
 * guess at it.
 *
 * <p>This validator does not itself replay anything and returns no context for doing so - a
 * separate replay-execution step consumes an already-validated {@code recording}/{@code workflow}
 * pair to reconstruct the recorded decision trace.
 */
public final class ReplayValidator {

    private ReplayValidator() {}

    /**
     * Returns {@link Optional#empty()} if {@code recording} may be replayed against {@code
     * workflow}, or a structured {@link ReplayValidationFailure} explaining why not.
     */
    public static Optional<ReplayValidationFailure> validate(
            WorkflowRecordingV2 recording, Workflow workflow) {
        Objects.requireNonNull(recording, "recording");
        Objects.requireNonNull(workflow, "workflow");
        if (recording.status() != WorkflowStatus.COMPLETED) {
            return Optional.of(
                    new ReplayValidationFailure(
                            ReplayFailureType.UNSUPPORTED_STATUS,
                            "only a COMPLETED recording can be replayed in this scope"));
        }
        if (!recording.plan().equals(WorkflowPlanner.plan(workflow))) {
            return Optional.of(
                    new ReplayValidationFailure(
                            ReplayFailureType.INCOMPATIBLE_WORKFLOW,
                            "recording's plan does not match the live workflow's current"
                                    + " structure"));
        }
        return checkLoopIterationBounds(recording.nodes(), recording.plan().nodes(), workflow);
    }

    /**
     * Recursively walks the recorded execution tree in lockstep, positionally, with its own
     * recorded plan - already proven identical to {@code WorkflowPlanner.plan(workflow)} by the
     * caller's equality check - and rejects the first recorded {@link WorkflowStepType#LOOP} whose
     * actual iteration count exceeds the live {@code workflow}'s own declared {@code maxIterations}
     * for that exact loop.
     *
     * <p><b>Why this walks the plan, not the recorded tree's own step IDs:</b> a nested loop's
     * recorded step ID carries a runtime iteration qualification (for example {@code "inner#0"},
     * composing further for a loop nested inside a loop - see {@code
     * WorkflowEngine.Session#qualify}), which never equals any declared {@link
     * io.webagent4j.workflow.WorkflowStepId} in the live workflow. A recorded plan node's {@code
     * stepId()} is never qualified this way - {@code WorkflowPlanner} builds a plan node directly
     * from a step's own declared ID, at every nesting depth, since a plan describes a loop's body
     * exactly once, structurally - so resolving {@link Workflow#loopMaxIterations(WorkflowStepId)}
     * against the positionally-aligned <em>plan</em> node's ID rather than the tree node's own ID
     * works correctly at any nesting depth, with no ID stripping or other string heuristic, and
     * naturally distinguishes multiple runtime occurrences of the same nested loop declaration
     * (each aligns with the identical single plan node, and is checked against the identical
     * bound).
     */
    private static Optional<ReplayValidationFailure> checkLoopIterationBounds(
            List<RecordedExecutionNodeV2> nodes,
            List<WorkflowPlanNode> planNodes,
            Workflow workflow) {
        for (int i = 0; i < nodes.size(); i++) {
            RecordedExecutionNodeV2 node = nodes.get(i);
            WorkflowPlanNode planNode = planNodes.get(i);
            if (planNode.stepType() == WorkflowStepType.LOOP) {
                Optional<ReplayValidationFailure> loopFailure = checkLoop(node, planNode, workflow);
                if (loopFailure.isPresent()) {
                    return loopFailure;
                }
                continue;
            }
            if (planNode.stepType() == WorkflowStepType.CONDITIONAL
                    && node.branchSelection().isPresent()) {
                WorkflowPlanBranch matchingBranch =
                        findBranch(planNode.branches(), node.branchSelection().get());
                if (matchingBranch != null) {
                    Optional<ReplayValidationFailure> nested =
                            checkLoopIterationBounds(
                                    node.children(), matchingBranch.nodes(), workflow);
                    if (nested.isPresent()) {
                        return nested;
                    }
                }
            }
        }
        return Optional.empty();
    }

    /**
     * Checks one recorded {@code LOOP} node against its positionally-aligned plan node, then
     * recurses into each authorized iteration's own body - reusing {@code bodyPlan} for every
     * iteration, exactly mirroring how a single declared body corresponds to every runtime
     * occurrence of it.
     */
    private static Optional<ReplayValidationFailure> checkLoop(
            RecordedExecutionNodeV2 loopNode, WorkflowPlanNode loopPlanNode, Workflow workflow) {
        if (loopNode.step().status() == WorkflowStepStatus.NOT_RUN) {
            return Optional.empty();
        }
        Optional<Integer> declaredMaxIterations = workflow.loopMaxIterations(loopPlanNode.stepId());
        if (declaredMaxIterations.isEmpty()) {
            // Structurally unreachable once the recording's plan has already been proven identical
            // to the live workflow's plan - kept as an explicit, fail-closed rejection rather than
            // an unbounded fallback, per this validator's own contract: a loop this validator
            // cannot resolve against a live declaration is never treated as unbounded.
            return Optional.of(
                    new ReplayValidationFailure(
                            ReplayFailureType.INCOMPATIBLE_WORKFLOW,
                            "recorded loop does not correspond to a resolvable live loop"
                                    + " declaration"));
        }
        int actualIterations = countAuthorizedIterations(loopNode.children());
        if (actualIterations > declaredMaxIterations.get()) {
            return Optional.of(
                    new ReplayValidationFailure(
                            ReplayFailureType.LOOP_ITERATION_COUNT_EXCEEDS_BOUND,
                            "a recorded loop ran more iterations than the live workflow's"
                                    + " declared bound authorizes"));
        }
        List<WorkflowPlanNode> bodyPlan = loopPlanNode.branches().get(0).nodes();
        for (RecordedExecutionNodeV2 iteration : loopNode.children()) {
            if (iteration.branchSelection().isEmpty()
                    || iteration.branchSelection().get() != WorkflowBranchSelection.THEN
                    || iteration.children().isEmpty()) {
                continue;
            }
            Optional<ReplayValidationFailure> nested =
                    checkLoopIterationBounds(iteration.children(), bodyPlan, workflow);
            if (nested.isPresent()) {
                return nested;
            }
        }
        return Optional.empty();
    }

    /**
     * Counts only the iterations {@code WorkflowEngine} actually authorized to run - a {@code true}
     * continuation outcome that selected {@code THEN} - never a bare {@code children().size() - 1},
     * which silently assumes every recorded loop ends in exactly one non-authorized terminal check.
     * A false-outcome stop ({@code NONE}) and a bound-exceeded check (no selection at all) are both
     * genuine continuation checks the loop performed, but neither is itself a run iteration.
     */
    private static int countAuthorizedIterations(List<RecordedExecutionNodeV2> iterations) {
        int count = 0;
        for (RecordedExecutionNodeV2 iteration : iterations) {
            if (iteration.branchSelection().isPresent()
                    && iteration.branchSelection().get() == WorkflowBranchSelection.THEN) {
                count++;
            }
        }
        return count;
    }

    private static WorkflowPlanBranch findBranch(
            List<WorkflowPlanBranch> branches, WorkflowBranchSelection selection) {
        for (WorkflowPlanBranch branch : branches) {
            if (branch.kind() == selection) {
                return branch;
            }
        }
        return null;
    }
}
