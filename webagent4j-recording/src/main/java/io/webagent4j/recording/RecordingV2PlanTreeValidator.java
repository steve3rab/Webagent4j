package io.webagent4j.recording;

import io.webagent4j.workflow.WorkflowBranchSelection;
import io.webagent4j.workflow.WorkflowPlanBranch;
import io.webagent4j.workflow.WorkflowPlanNode;
import io.webagent4j.workflow.WorkflowPlanOutput;
import io.webagent4j.workflow.WorkflowStepType;
import java.util.List;
import java.util.Optional;

/**
 * Validates that a {@link WorkflowRecordingV2}'s {@link RecordedExecutionNodeV2} tree is itself a
 * genuine, structurally authorized path through that same recording's {@link
 * io.webagent4j.workflow.WorkflowExecutionPlan} - the cross-check {@link WorkflowRecordingV2}'s
 * other invariants ({@link RecordingV2Invariants}) deliberately leave to this class, factored out
 * for the identical "one call, every construction path" reason.
 *
 * <p>Without this check, a recording's {@code plan()} and {@code nodes()} are only independently
 * well-shaped - nothing stops a hostile or corrupted recording from pairing a plan that matches a
 * live workflow exactly with a completely different, fabricated execution tree. {@link #validate}
 * closes that gap so a {@link WorkflowRecordingV2} instance can never exist unless its tree is
 * already proven consistent with its own plan - Deterministic Replay's {@code ReplayValidator} then
 * only has to additionally check the plan itself against the live workflow, never the tree against
 * the plan.
 *
 * <p><b>Matching is strictly positional, with no fallback lookup:</b> the node at index {@code i}
 * of a level is checked only against the plan node at index {@code i} of the same level - never
 * searched for by step ID elsewhere in the plan. This is sound because both {@code WorkflowPlanner}
 * and {@code WorkflowEngine} derive every level's ordering from the identical underlying step
 * declarations, in the same order, so a genuine recording's tree and plan are always positionally
 * aligned; a recording that is not is exactly what must be rejected.
 *
 * <p><b>Complexity:</b> {@link #validate} visits each plan node at most twice - once while checking
 * the execution tree (only the branch each conditional actually selected, exactly mirroring the
 * tree's own zero-nodes-for-the-unselected-branch shape) and once, independently, while confirming
 * the plan's own nesting depth (which must visit both branches, since the tree alone could never
 * reveal excessive depth hidden only in a branch nothing selected) - overall linear in the plan's
 * size, never exponential, and never enumerating branch-outcome combinations.
 *
 * <p><b>Depth is bounded before recursing, not after:</b> {@link #MAX_TREE_DEPTH} is this module's
 * single source of truth for the maximum supported conditional-nesting depth, checked at each level
 * before either recursion descends one level further - so neither this validator's own call stack,
 * nor a well-formed decoder or encoder using the same constant and the same check-before
 * discipline, can be driven into a {@link StackOverflowError} by an arbitrarily deep hostile input.
 * Depth is 1 for a top-level {@link WorkflowStepType#CONDITIONAL} step, 2 for one nested in either
 * of its branches, and so on - the same semantics {@code Workflow.Builder#build()} already enforces
 * for a live workflow definition via its own (inaccessible outside {@code io.webagent4j.workflow})
 * nesting-depth constant of the same value.
 */
final class RecordingV2PlanTreeValidator {

    /**
     * Maximum supported conditional-nesting depth for both a recording's plan and its execution
     * tree - this module's single source of truth, reused by {@link JsonWorkflowRecordingV2Codec}
     * for its own encode- and decode-side checks rather than each keeping an independent copy.
     */
    static final int MAX_TREE_DEPTH = 64;

    private RecordingV2PlanTreeValidator() {}

    /**
     * Validates that {@code nodes} is a structurally authorized path through {@code planNodes}, and
     * that {@code planNodes} does not itself exceed {@link #MAX_TREE_DEPTH}.
     *
     * @throws IllegalArgumentException if {@code nodes} is inconsistent with {@code planNodes} at
     *     any level, or either structure's nesting depth exceeds {@link #MAX_TREE_DEPTH}
     */
    static void validate(List<RecordedExecutionNodeV2> nodes, List<WorkflowPlanNode> planNodes) {
        validatePlanDepth(planNodes, 0);
        validateLevel(nodes, planNodes, 0);
    }

    private static void validatePlanDepth(List<WorkflowPlanNode> planNodes, int depth) {
        for (WorkflowPlanNode planNode : planNodes) {
            if (planNode.stepType() != WorkflowStepType.CONDITIONAL) {
                continue;
            }
            int childDepth = depth + 1;
            if (childDepth > MAX_TREE_DEPTH) {
                throw new IllegalArgumentException(
                        "recorded plan exceeds the maximum supported nesting depth");
            }
            for (WorkflowPlanBranch branch : planNode.branches()) {
                validatePlanDepth(branch.nodes(), childDepth);
            }
        }
    }

    private static void validateLevel(
            List<RecordedExecutionNodeV2> nodes, List<WorkflowPlanNode> planNodes, int depth) {
        if (nodes.size() != planNodes.size()) {
            throw new IllegalArgumentException(
                    "recorded execution nodes do not match the recorded plan's node count at this"
                            + " level");
        }
        for (int i = 0; i < nodes.size(); i++) {
            validateNode(nodes.get(i), planNodes.get(i), depth);
        }
    }

    private static void validateNode(
            RecordedExecutionNodeV2 node, WorkflowPlanNode planNode, int depth) {
        RecordedWorkflowStepV2 step = node.step();
        if (!step.stepId().equals(planNode.stepId())) {
            throw new IllegalArgumentException(
                    "recorded step ID does not match the recorded plan at this position");
        }
        if (step.stepType() != planNode.stepType()) {
            throw new IllegalArgumentException(
                    "recorded step type does not match the recorded plan at this position");
        }
        validateOutput(step.output(), planNode.declaredOutput());
        if (planNode.stepType() != WorkflowStepType.CONDITIONAL) {
            return;
        }
        if (node.branchSelection().isEmpty()) {
            return;
        }
        WorkflowBranchSelection selection = node.branchSelection().get();
        WorkflowPlanBranch matchingBranch = findBranch(planNode.branches(), selection);
        if (matchingBranch == null) {
            throw new IllegalArgumentException(
                    "recorded branch selection is not structurally possible for this recorded"
                            + " plan node");
        }
        int childDepth = depth + 1;
        if (childDepth > MAX_TREE_DEPTH) {
            throw new IllegalArgumentException(
                    "recorded execution tree exceeds the maximum supported nesting depth");
        }
        validateLevel(node.children(), matchingBranch.nodes(), childDepth);
    }

    private static void validateOutput(
            Optional<WorkflowPlanOutput> recorded, Optional<WorkflowPlanOutput> declared) {
        if (declared.isEmpty() && recorded.isPresent()) {
            throw new IllegalArgumentException(
                    "recorded step published an output the recorded plan does not declare for it");
        }
        if (declared.isPresent()
                && recorded.isPresent()
                && !recorded.get().equals(declared.get())) {
            throw new IllegalArgumentException(
                    "recorded output does not match its declaration in the recorded plan");
        }
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
