package io.webagent4j.recording;

import io.webagent4j.workflow.WorkflowBranchSelection;
import io.webagent4j.workflow.WorkflowFailureType;
import io.webagent4j.workflow.WorkflowPlanBranch;
import io.webagent4j.workflow.WorkflowPlanNode;
import io.webagent4j.workflow.WorkflowPlanOutput;
import io.webagent4j.workflow.WorkflowStepStatus;
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
 * <p><b>A {@code CONDITIONAL} node's captured decision must be one {@code WorkflowEngine} can
 * actually produce</b> (see {@link #validateConditionalDecision}): {@link
 * RecordedWorkflowStepV2#condition()} and the node's {@code branchSelection} are always both
 * present or both absent - never one without the other - a {@code SUCCEEDED} conditional always has
 * both, and a present outcome always agrees with the selection it implies ({@code true} only ever
 * pairs with {@code THEN}, {@code false} only ever pairs with a non-{@code THEN} branch this plan
 * node actually declares). A conditional step is never {@code SKIPPED} - {@code
 * ConditionalWorkflowStep} does not support the generic {@code when(...)} guard other step types
 * do, precisely because its one condition slot already carries the mandatory branch-selector
 * meaning - so a decision that merely says a condition succeeded without ever recording what it
 * selected is exactly the ambiguous, unreplayable state this check exists to reject.
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
        validateLevel(nodes, planNodes, 0, "");
    }

    private static void validatePlanDepth(List<WorkflowPlanNode> planNodes, int depth) {
        for (WorkflowPlanNode planNode : planNodes) {
            if (planNode.stepType() != WorkflowStepType.CONDITIONAL
                    && planNode.stepType() != WorkflowStepType.LOOP
                    && planNode.stepType() != WorkflowStepType.PARALLEL) {
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
            List<RecordedExecutionNodeV2> nodes,
            List<WorkflowPlanNode> planNodes,
            int depth,
            String idSuffix) {
        if (nodes.size() != planNodes.size()) {
            throw new IllegalArgumentException(
                    "recorded execution nodes do not match the recorded plan's node count at this"
                            + " level");
        }
        for (int i = 0; i < nodes.size(); i++) {
            validateNode(nodes.get(i), planNodes.get(i), depth, idSuffix);
        }
    }

    /**
     * Validates one node against its positionally-matched plan node. {@code idSuffix} is the
     * iteration qualification this node's step ID is expected to carry - empty everywhere except
     * inside a {@link WorkflowStepType#LOOP} body, where it is {@code "#<iteration>"} (composing
     * for nested loops), mirroring exactly how {@code WorkflowEngine.Session#qualify} constructs a
     * loop iteration's real step IDs. The plan itself never carries this suffix - a {@link
     * io.webagent4j.workflow.WorkflowExecutionPlan} describes a loop's body exactly once - so the
     * expected ID is always {@code planNode.stepId() + idSuffix}.
     */
    private static void validateNode(
            RecordedExecutionNodeV2 node, WorkflowPlanNode planNode, int depth, String idSuffix) {
        RecordedWorkflowStepV2 step = node.step();
        if (!step.stepId().value().equals(planNode.stepId().value() + idSuffix)) {
            throw new IllegalArgumentException(
                    "recorded step ID does not match the recorded plan at this position");
        }
        if (step.stepType() != planNode.stepType()) {
            throw new IllegalArgumentException(
                    "recorded step type does not match the recorded plan at this position");
        }
        validateOutput(step.output(), planNode.declaredOutput());
        if (planNode.stepType() == WorkflowStepType.LOOP) {
            validateLoopNode(node, planNode, depth, idSuffix);
            return;
        }
        if (planNode.stepType() == WorkflowStepType.PARALLEL) {
            validateParallelNode(node, planNode, depth, idSuffix);
            return;
        }
        if (planNode.stepType() != WorkflowStepType.CONDITIONAL) {
            return;
        }
        validateConditionalDecision(step, node.branchSelection());
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
        validateLevel(node.children(), matchingBranch.nodes(), childDepth, idSuffix);
    }

    /**
     * Validates a recorded {@link WorkflowStepType#LOOP} node: it never itself carries a branch
     * selection (see {@link RecordedExecutionNodeV2}'s own invariant), and each of its children is
     * one {@link WorkflowStepType#LOOP_ITERATION} continuation check, in order, whose own step ID
     * must be {@code loopId + idSuffix + "#" + iterationIndex} - hostile input in the count, shape,
     * ID, or decision of any of them is rejected. The live {@code maxIterations} bound itself is
     * deliberately not checked here - a {@link io.webagent4j.workflow.WorkflowExecutionPlan} never
     * encodes it, so only {@code io.webagent4j.recording.replay.ReplayValidator}, which has the
     * live {@link io.webagent4j.workflow.Workflow} available, can (see its own Javadoc).
     */
    private static void validateLoopNode(
            RecordedExecutionNodeV2 node, WorkflowPlanNode planNode, int depth, String idSuffix) {
        if (node.branchSelection().isPresent()) {
            throw new IllegalArgumentException(
                    "a recorded LOOP node must never carry a branch selection");
        }
        int childDepth = depth + 1;
        if (childDepth > MAX_TREE_DEPTH) {
            throw new IllegalArgumentException(
                    "recorded execution tree exceeds the maximum supported nesting depth");
        }
        List<WorkflowPlanNode> bodyPlan = planNode.branches().get(0).nodes();
        List<RecordedExecutionNodeV2> iterations = node.children();
        for (int i = 0; i < iterations.size(); i++) {
            String iterationSuffix = idSuffix + "#" + i;
            validateLoopIterationNode(
                    iterations.get(i),
                    planNode.stepId().value(),
                    bodyPlan,
                    childDepth,
                    iterationSuffix);
        }
    }

    /**
     * Validates one recorded {@link WorkflowStepType#LOOP_ITERATION} against the three - and only
     * three - states {@code WorkflowEngine.Session#executeLoopStepInto} can actually produce for
     * it:
     *
     * <ul>
     *   <li>no captured outcome (evaluation itself failed, or interrupted before it could run): no
     *       selection, no children.
     *   <li>a {@code false} outcome: always {@link WorkflowBranchSelection#NONE}, zero children,
     *       never {@link WorkflowStepStatus#FAILED} - a false outcome is always a successful no-op,
     *       exactly like {@code ifThen}'s own false decision.
     *   <li>a {@code true} outcome: either {@link WorkflowBranchSelection#THEN} (the iteration was
     *       authorized) or no selection at all, exclusively when {@code status} is {@link
     *       WorkflowStepStatus#FAILED} with {@link
     *       WorkflowFailureType#LOOP_ITERATION_LIMIT_EXCEEDED} - the bound was reached while still
     *       {@code true}, so the iteration was never authorized to start at all. {@link
     *       WorkflowBranchSelection#ELSE} is never structurally possible for a loop iteration. A
     *       {@code THEN} selection's children must match {@code bodyPlan} exactly (validated
     *       positionally, exactly like a conditional branch) whenever {@code bodyPlan} is
     *       non-empty, with exactly one exception: {@code status} {@link WorkflowStepStatus#FAILED}
     *       with {@link WorkflowFailureType#LOOP_STEP_INTERRUPTED} - the one state where the thread
     *       was interrupted after the decision was captured but before {@code runSteps} was ever
     *       invoked for the body, so zero children were ever produced no matter how large {@code
     *       bodyPlan} is. A {@code SUCCEEDED} {@code THEN} iteration may only ever carry zero
     *       children when {@code bodyPlan} itself is empty - never as a stand-in for a body that
     *       was authorized but never actually recorded.
     * </ul>
     */
    private static void validateLoopIterationNode(
            RecordedExecutionNodeV2 iterationNode,
            String loopStepId,
            List<WorkflowPlanNode> bodyPlan,
            int depth,
            String iterationSuffix) {
        RecordedWorkflowStepV2 step = iterationNode.step();
        if (step.stepType() != WorkflowStepType.LOOP_ITERATION) {
            throw new IllegalArgumentException(
                    "a LOOP node's recorded children must all be LOOP_ITERATION steps");
        }
        if (!step.stepId().value().equals(loopStepId + iterationSuffix)) {
            throw new IllegalArgumentException(
                    "recorded LOOP_ITERATION step ID does not match its expected iteration"
                            + " qualification");
        }
        if (step.output().isPresent()) {
            throw new IllegalArgumentException("a LOOP_ITERATION cannot carry a published output");
        }
        Optional<RecordedCondition> condition = step.condition();
        Optional<WorkflowBranchSelection> selection = iterationNode.branchSelection();

        if (condition.isEmpty()) {
            if (selection.isPresent() || !iterationNode.children().isEmpty()) {
                throw new IllegalArgumentException(
                        "a LOOP_ITERATION with no captured condition outcome cannot carry a branch"
                                + " selection or children");
            }
            return;
        }

        if (!condition.get().outcome()) {
            if (selection.isEmpty() || selection.get() != WorkflowBranchSelection.NONE) {
                throw new IllegalArgumentException(
                        "a LOOP_ITERATION with a false condition outcome must select NONE");
            }
            if (!iterationNode.children().isEmpty()) {
                throw new IllegalArgumentException(
                        "a LOOP_ITERATION that selected NONE must carry zero children");
            }
            if (step.status() == WorkflowStepStatus.FAILED) {
                throw new IllegalArgumentException(
                        "a LOOP_ITERATION with a false condition outcome is never FAILED");
            }
            return;
        }

        if (selection.isEmpty()) {
            boolean isBoundExceeded =
                    step.status() == WorkflowStepStatus.FAILED
                            && step.failure().isPresent()
                            && step.failure().get().type()
                                    == WorkflowFailureType.LOOP_ITERATION_LIMIT_EXCEEDED;
            if (!isBoundExceeded) {
                throw new IllegalArgumentException(
                        "a LOOP_ITERATION with a true condition outcome and no branch selection"
                                + " must be the bound-exceeded failure");
            }
            if (!iterationNode.children().isEmpty()) {
                throw new IllegalArgumentException(
                        "a bound-exceeded LOOP_ITERATION must carry zero children");
            }
            return;
        }
        if (selection.get() != WorkflowBranchSelection.THEN) {
            throw new IllegalArgumentException(
                    "a LOOP_ITERATION with a true condition outcome can only select THEN or carry"
                            + " no selection at all");
        }
        boolean interruptedBeforeBody = isInterruptedBeforeBody(step);
        if (iterationNode.children().isEmpty()) {
            if (!bodyPlan.isEmpty() && !interruptedBeforeBody) {
                throw new IllegalArgumentException(
                        "a LOOP_ITERATION that selected THEN for a non-empty loop body must carry"
                                + " that body's own recorded children - WorkflowEngine only ever"
                                + " leaves a THEN iteration's children empty for a genuinely empty"
                                + " declared body, or when interrupted before the body could"
                                + " start");
            }
            return;
        }
        if (interruptedBeforeBody) {
            throw new IllegalArgumentException(
                    "a LOOP_ITERATION recorded as interrupted before its body started must carry"
                            + " zero children");
        }
        validateLevel(iterationNode.children(), bodyPlan, depth, iterationSuffix);
    }

    /**
     * True exactly for the one {@code true}-outcome, {@code THEN}-selected state {@code
     * WorkflowEngine.Session#executeLoopStepInto} can produce with zero children despite a
     * non-empty declared body: the executing thread was interrupted after the continuation decision
     * was captured but before {@code runSteps} was ever invoked for the body, so the iteration's
     * own result carries {@link WorkflowFailureType#LOOP_STEP_INTERRUPTED} directly (via {@code
     * addInterrupted}) rather than deferring to a child step's failure the way every other
     * loop-body failure does.
     */
    private static boolean isInterruptedBeforeBody(RecordedWorkflowStepV2 step) {
        return step.status() == WorkflowStepStatus.FAILED
                && step.failure().isPresent()
                && step.failure().get().type() == WorkflowFailureType.LOOP_STEP_INTERRUPTED;
    }

    /**
     * Validates a recorded {@link WorkflowStepType#PARALLEL} node - added in 1.3.0: it never itself
     * carries a branch selection (see {@link RecordedExecutionNodeV2}'s own invariant). A status
     * other than {@link WorkflowStepStatus#SUCCEEDED} or the one {@code FAILED} exception below
     * (skipped by its own optional guard, failed before any branch launched, or never reached) must
     * carry zero children - no branch is ever launched in any of those cases.
     *
     * <p>A node may also carry branches while {@code FAILED} with {@link
     * WorkflowFailureType#PARALLEL_STEP_INTERRUPTED} - added in 1.3.0 for the calling thread's own
     * interruption while joining an already-launched step's branches (as opposed to the same
     * failure type's other, pre-launch boundary, which carries zero children exactly like any other
     * pre-launch failure): {@link WorkflowResult}'s own pre-existing global invariant (every step
     * after a {@code FAILED} one must be {@code NOT_RUN}) means none of those branches can ever be
     * {@code SUCCEEDED} - {@code WorkflowEngine} represents every one of them as {@link
     * WorkflowStepStatus#NOT_RUN} with zero children, unconditionally, regardless of how much of
     * any branch's own work had actually completed before the interruption arrived (see {@code
     * WorkflowEngine.Session#joinInterruptedParallelBranches}). This method enforces that narrower
     * shape specifically for a {@code FAILED} node - see the {@code SUCCEEDED} check below - rather
     * than merely reusing the same per-branch count/shape check a {@code SUCCEEDED} node's own
     * launched branches are held to.
     *
     * <p>Either accepted shape must carry exactly one {@link WorkflowStepType#PARALLEL_BRANCH}
     * child per branch this plan node declares, positionally matched (never a different count -
     * {@code Workflow.MIN_PARALLEL_BRANCHES} guarantees the plan itself always declares at least
     * two).
     */
    private static void validateParallelNode(
            RecordedExecutionNodeV2 node, WorkflowPlanNode planNode, int depth, String idSuffix) {
        if (node.branchSelection().isPresent()) {
            throw new IllegalArgumentException(
                    "a recorded PARALLEL node must never carry a branch selection");
        }
        if (!parallelNodeMayCarryBranches(node.step())) {
            if (!node.children().isEmpty()) {
                throw new IllegalArgumentException(
                        "a recorded PARALLEL node that did not launch any branch must carry zero"
                                + " children - either it succeeded without launching one, or it"
                                + " failed before any branch could be launched");
            }
            return;
        }
        List<WorkflowPlanBranch> branches = planNode.branches();
        if (node.children().size() != branches.size()) {
            throw new IllegalArgumentException(
                    "a SUCCEEDED recorded PARALLEL node's branch count does not match its recorded"
                            + " plan");
        }
        boolean interrupted = node.step().status() == WorkflowStepStatus.FAILED;
        if (interrupted) {
            for (RecordedExecutionNodeV2 branch : node.children()) {
                if (branch.step().status() != WorkflowStepStatus.NOT_RUN) {
                    throw new IllegalArgumentException(
                            "a FAILED/PARALLEL_STEP_INTERRUPTED recorded PARALLEL node's own"
                                    + " branches must all be NOT_RUN - WorkflowResult's global"
                                    + " invariant forbids anything but NOT_RUN after a FAILED"
                                    + " step, so no branch can ever be recorded as SUCCEEDED"
                                    + " alongside it");
                }
            }
        }
        int childDepth = depth + 1;
        if (childDepth > MAX_TREE_DEPTH) {
            throw new IllegalArgumentException(
                    "recorded execution tree exceeds the maximum supported nesting depth");
        }
        for (int i = 0; i < node.children().size(); i++) {
            validateParallelBranchNode(
                    node.children().get(i),
                    planNode.stepId().value(),
                    branches.get(i).nodes(),
                    childDepth,
                    idSuffix,
                    i);
        }
    }

    /**
     * True for the two states {@code WorkflowEngine.Session#executeParallelStepInto} can produce
     * with branches recorded underneath: a {@code SUCCEEDED} step (every branch was launched), or a
     * {@code FAILED} step whose failure is {@link WorkflowFailureType#PARALLEL_STEP_INTERRUPTED}
     * and whose interruption was observed <em>while joining</em> already-launched branches rather
     * than before any of them could launch - the latter, pre-launch case is indistinguishable from
     * any other pre-launch failure only by checking whether the recorded node actually carries
     * children, which is exactly the shape check the caller already performs; this method exists
     * purely to decide, for a {@code FAILED} node, whether the {@code PARALLEL_STEP_INTERRUPTED}
     * failure type is even structurally possible with branches underneath it, never to duplicate
     * that count check itself.
     */
    private static boolean parallelNodeMayCarryBranches(RecordedWorkflowStepV2 step) {
        if (step.status() == WorkflowStepStatus.SUCCEEDED) {
            return true;
        }
        return step.status() == WorkflowStepStatus.FAILED
                && step.failure().isPresent()
                && step.failure().get().type() == WorkflowFailureType.PARALLEL_STEP_INTERRUPTED;
    }

    /**
     * Validates one recorded {@link WorkflowStepType#PARALLEL_BRANCH} against its positionally-
     * matched plan branch - added in 1.3.0. Its own step ID must be {@code parallelStepId +
     * idSuffix + "@" + branchIndex}, mirroring exactly how {@code
     * WorkflowEngine.Session#executeParallelStepInto} qualifies a real branch's own step IDs.
     * {@link WorkflowStepStatus#NOT_RUN} (this branch was never launched - either an
     * earlier-declared sibling branch failed first, or the whole step never reached this branch)
     * must carry zero children; {@link WorkflowStepStatus#SUCCEEDED} (this branch was launched -
     * its own outcome, success or failure, is carried by its children) must carry at least one
     * child, matched exactly like any other level via {@link #validateLevel}, since a declared
     * branch is never empty ({@code WorkflowSteps#parallel} rejects an empty branch outright). No
     * other status is structurally possible for a {@code PARALLEL_BRANCH}'s own wrapper result (see
     * {@link RecordedWorkflowStepV2}'s own invariant, already enforced independently of this
     * check).
     */
    private static void validateParallelBranchNode(
            RecordedExecutionNodeV2 branchNode,
            String parallelStepId,
            List<WorkflowPlanNode> branchPlan,
            int depth,
            String idSuffix,
            int branchIndex) {
        RecordedWorkflowStepV2 step = branchNode.step();
        if (step.stepType() != WorkflowStepType.PARALLEL_BRANCH) {
            throw new IllegalArgumentException(
                    "a PARALLEL node's recorded children must all be PARALLEL_BRANCH steps");
        }
        String branchSuffix = idSuffix + "@" + branchIndex;
        if (!step.stepId().value().equals(parallelStepId + branchSuffix)) {
            throw new IllegalArgumentException(
                    "recorded PARALLEL_BRANCH step ID does not match its expected branch"
                            + " qualification");
        }
        if (branchNode.branchSelection().isPresent()) {
            throw new IllegalArgumentException(
                    "a recorded PARALLEL_BRANCH node must never carry a branch selection");
        }
        if (step.status() == WorkflowStepStatus.NOT_RUN) {
            if (!branchNode.children().isEmpty()) {
                throw new IllegalArgumentException(
                        "a NOT_RUN recorded PARALLEL_BRANCH must carry zero children - it was"
                                + " never launched");
            }
            return;
        }
        if (branchNode.children().isEmpty()) {
            throw new IllegalArgumentException(
                    "a SUCCEEDED recorded PARALLEL_BRANCH must carry its own branch's recorded"
                            + " steps as children - a declared branch is never empty");
        }
        validateLevel(branchNode.children(), branchPlan, depth, branchSuffix);
    }

    /**
     * Validates that a {@code CONDITIONAL} step's captured decision - {@link
     * RecordedWorkflowStepV2#condition()} and the enclosing node's {@code branchSelection} -
     * matches exactly one of the states {@code WorkflowEngine} can actually produce (see {@code
     * WorkflowEngine.Session#executeConditionalStepInto}): the condition and the branch selection
     * are always captured together or not at all - never one without the other - a {@code
     * SUCCEEDED} step always has both, and whichever selection is present always agrees with the
     * captured outcome ({@code true} only ever selects {@code THEN}; {@code false} only ever
     * selects a non-{@code THEN} branch, which of the two is checked separately, against the plan's
     * actual branch shape, by {@link #validateNode}'s caller).
     */
    private static void validateConditionalDecision(
            RecordedWorkflowStepV2 step, Optional<WorkflowBranchSelection> branchSelection) {
        Optional<RecordedCondition> condition = step.condition();
        if (condition.isPresent() != branchSelection.isPresent()) {
            throw new IllegalArgumentException(
                    "a recorded CONDITIONAL step's condition outcome and branch selection must be"
                            + " captured together or not at all");
        }
        if (step.status() == WorkflowStepStatus.SUCCEEDED && condition.isEmpty()) {
            throw new IllegalArgumentException(
                    "a SUCCEEDED CONDITIONAL step must carry the branch decision it captured");
        }
        if (condition.isEmpty()) {
            return;
        }
        boolean outcome = condition.get().outcome();
        WorkflowBranchSelection selection = branchSelection.get();
        boolean consistentWithOutcome =
                outcome
                        ? selection == WorkflowBranchSelection.THEN
                        : selection != WorkflowBranchSelection.THEN;
        if (!consistentWithOutcome) {
            throw new IllegalArgumentException(
                    "recorded branch selection is not consistent with the recorded condition"
                            + " outcome");
        }
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
