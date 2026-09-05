package io.webagent4j.recording;

import io.webagent4j.action.ActionExecutionMode;
import io.webagent4j.action.ActionStatus;
import io.webagent4j.action.ActionType;
import io.webagent4j.workflow.WorkflowBranchSelection;
import io.webagent4j.workflow.WorkflowExecutionPlan;
import io.webagent4j.workflow.WorkflowFailureType;
import io.webagent4j.workflow.WorkflowId;
import io.webagent4j.workflow.WorkflowPlanBranch;
import io.webagent4j.workflow.WorkflowPlanNode;
import io.webagent4j.workflow.WorkflowPlanOutput;
import io.webagent4j.workflow.WorkflowStatus;
import io.webagent4j.workflow.WorkflowStepId;
import io.webagent4j.workflow.WorkflowStepStatus;
import io.webagent4j.workflow.WorkflowStepType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Test-only helpers for building minimal, valid {@link WorkflowRecordingV2} fixtures directly - the
 * tree-shaped counterpart of {@link RecordingFixtures}, reusing its {@link RecordedFailure}
 * builders and {@link RecordedAction} helper since those types are shared unchanged between V1 and
 * V2.
 */
final class RecordingV2Fixtures {

    private RecordingV2Fixtures() {}

    static WorkflowPlanOutput output(String name, String typeName, boolean secret) {
        return new WorkflowPlanOutput(name, typeName, secret);
    }

    static RecordedWorkflowStepV2 succeededActionStep(
            String stepId, Optional<WorkflowPlanOutput> output) {
        return new RecordedWorkflowStepV2(
                new WorkflowStepId(stepId),
                WorkflowStepType.ACTION,
                WorkflowStepStatus.SUCCEEDED,
                Optional.empty(),
                output,
                Optional.empty(),
                Optional.of(
                        RecordingFixtures.action(
                                ActionType.CLICK, ActionStatus.SUCCESS, ActionExecutionMode.REAL)));
    }

    static RecordedWorkflowStepV2 succeededAssignStep(String stepId, WorkflowPlanOutput output) {
        return new RecordedWorkflowStepV2(
                new WorkflowStepId(stepId),
                WorkflowStepType.ASSIGN,
                WorkflowStepStatus.SUCCEEDED,
                Optional.empty(),
                Optional.of(output),
                Optional.empty(),
                Optional.empty());
    }

    static RecordedWorkflowStepV2 skippedStep(String stepId, String description) {
        return new RecordedWorkflowStepV2(
                new WorkflowStepId(stepId),
                WorkflowStepType.ACTION,
                WorkflowStepStatus.SKIPPED,
                Optional.of(new RecordedCondition(false, description)),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
    }

    static RecordedWorkflowStepV2 notRunStep(String stepId) {
        return new RecordedWorkflowStepV2(
                new WorkflowStepId(stepId),
                WorkflowStepType.ACTION,
                WorkflowStepStatus.NOT_RUN,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
    }

    static RecordedWorkflowStepV2 notRunAssignStep(String stepId) {
        return new RecordedWorkflowStepV2(
                new WorkflowStepId(stepId),
                WorkflowStepType.ASSIGN,
                WorkflowStepStatus.NOT_RUN,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
    }

    static RecordedWorkflowStepV2 notRunConditionalStep(String stepId) {
        return new RecordedWorkflowStepV2(
                new WorkflowStepId(stepId),
                WorkflowStepType.CONDITIONAL,
                WorkflowStepStatus.NOT_RUN,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
    }

    static RecordedWorkflowStepV2 conditionalStep(String stepId, boolean outcome) {
        return new RecordedWorkflowStepV2(
                new WorkflowStepId(stepId),
                WorkflowStepType.CONDITIONAL,
                WorkflowStepStatus.SUCCEEDED,
                Optional.of(new RecordedCondition(outcome, "d")),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
    }

    /**
     * A {@code SUCCEEDED} CONDITIONAL step with no captured condition at all - an engine-impossible
     * shape {@code RecordingV2PlanTreeValidator} rejects, since a succeeded decision is never made
     * without capturing what it decided.
     */
    static RecordedWorkflowStepV2 conditionalStepWithoutCondition(String stepId) {
        return new RecordedWorkflowStepV2(
                new WorkflowStepId(stepId),
                WorkflowStepType.CONDITIONAL,
                WorkflowStepStatus.SUCCEEDED,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
    }

    static RecordedWorkflowStepV2 actionStepFailedWithSummary(
            String stepId,
            RecordedFailure failure,
            ActionStatus actionStatus,
            ActionExecutionMode executionMode) {
        return new RecordedWorkflowStepV2(
                new WorkflowStepId(stepId),
                WorkflowStepType.ACTION,
                WorkflowStepStatus.FAILED,
                Optional.empty(),
                Optional.empty(),
                Optional.of(failure),
                Optional.of(
                        RecordingFixtures.action(ActionType.CLICK, actionStatus, executionMode)));
    }

    static RecordedExecutionNodeV2 leaf(RecordedWorkflowStepV2 step) {
        return new RecordedExecutionNodeV2(step, Optional.empty(), List.of());
    }

    static RecordedExecutionNodeV2 conditionalNode(
            RecordedWorkflowStepV2 step,
            WorkflowBranchSelection selection,
            List<RecordedExecutionNodeV2> children) {
        return new RecordedExecutionNodeV2(step, Optional.of(selection), children);
    }

    static WorkflowExecutionPlan minimalPlan(String workflowId) {
        return new WorkflowExecutionPlan(
                new WorkflowId(workflowId),
                List.of(
                        new WorkflowPlanNode(
                                new WorkflowStepId("step-1"),
                                WorkflowStepType.ASSIGN,
                                false,
                                Optional.of(output("output", "String", false)),
                                List.of())));
    }

    static WorkflowExecutionPlan branchingPlan(String workflowId) {
        WorkflowPlanNode thenStep =
                new WorkflowPlanNode(
                        new WorkflowStepId("then-1"),
                        WorkflowStepType.ACTION,
                        false,
                        Optional.empty(),
                        List.of());
        return new WorkflowExecutionPlan(
                new WorkflowId(workflowId),
                List.of(
                        new WorkflowPlanNode(
                                new WorkflowStepId("cond-1"),
                                WorkflowStepType.CONDITIONAL,
                                false,
                                Optional.empty(),
                                List.of(
                                        new WorkflowPlanBranch(
                                                WorkflowBranchSelection.THEN, List.of(thenStep)),
                                        new WorkflowPlanBranch(
                                                WorkflowBranchSelection.ELSE, List.of())))));
    }

    static WorkflowRecordingV2 recordingWith(
            String workflowId,
            WorkflowExecutionPlan plan,
            WorkflowStatus status,
            List<RecordedExecutionNodeV2> nodes,
            Optional<RecordedFailure> failure) {
        return new WorkflowRecordingV2(
                RecordingSchemaVersionV2.V2,
                new RecordingId("recording-1"),
                Instant.parse("2026-01-01T00:00:00Z"),
                new WorkflowId(workflowId),
                status,
                plan,
                nodes,
                failure);
    }

    static WorkflowRecordingV2 minimalCompleted(String workflowId) {
        WorkflowPlanOutput out = output("output", "String", false);
        return recordingWith(
                workflowId,
                minimalPlan(workflowId),
                WorkflowStatus.COMPLETED,
                List.of(leaf(succeededAssignStep("step-1", out))),
                Optional.empty());
    }

    /** A CONDITIONAL plan node ("cond-1") whose ELSE branch also carries a real step ("else-1"). */
    static WorkflowExecutionPlan branchingPlanWithElseStep(String workflowId) {
        WorkflowPlanNode thenStep = actionPlanNode("then-1");
        WorkflowPlanNode elseStep = actionPlanNode("else-1");
        return new WorkflowExecutionPlan(
                new WorkflowId(workflowId),
                List.of(
                        new WorkflowPlanNode(
                                new WorkflowStepId("cond-1"),
                                WorkflowStepType.CONDITIONAL,
                                false,
                                Optional.empty(),
                                List.of(
                                        new WorkflowPlanBranch(
                                                WorkflowBranchSelection.THEN, List.of(thenStep)),
                                        new WorkflowPlanBranch(
                                                WorkflowBranchSelection.ELSE,
                                                List.of(elseStep))))));
    }

    /** A CONDITIONAL plan node ("cond-1") whose THEN branch carries two sequential steps. */
    static WorkflowExecutionPlan twoStepThenBranchPlan(String workflowId) {
        WorkflowPlanNode thenStep1 = actionPlanNode("then-1");
        WorkflowPlanNode thenStep2 = actionPlanNode("then-2");
        return new WorkflowExecutionPlan(
                new WorkflowId(workflowId),
                List.of(
                        new WorkflowPlanNode(
                                new WorkflowStepId("cond-1"),
                                WorkflowStepType.CONDITIONAL,
                                false,
                                Optional.empty(),
                                List.of(
                                        new WorkflowPlanBranch(
                                                WorkflowBranchSelection.THEN,
                                                List.of(thenStep1, thenStep2)),
                                        new WorkflowPlanBranch(
                                                WorkflowBranchSelection.ELSE, List.of())))));
    }

    /** An {@code ifThen}-shaped CONDITIONAL plan node ("cond-1"): THEN plus a structural NONE. */
    static WorkflowExecutionPlan ifThenPlan(String workflowId) {
        WorkflowPlanNode thenStep = actionPlanNode("then-1");
        return new WorkflowExecutionPlan(
                new WorkflowId(workflowId),
                List.of(
                        new WorkflowPlanNode(
                                new WorkflowStepId("cond-1"),
                                WorkflowStepType.CONDITIONAL,
                                false,
                                Optional.empty(),
                                List.of(
                                        new WorkflowPlanBranch(
                                                WorkflowBranchSelection.THEN, List.of(thenStep)),
                                        new WorkflowPlanBranch(
                                                WorkflowBranchSelection.NONE, List.of())))));
    }

    private static WorkflowPlanNode actionPlanNode(String stepId) {
        return new WorkflowPlanNode(
                new WorkflowStepId(stepId),
                WorkflowStepType.ACTION,
                false,
                Optional.empty(),
                List.of());
    }

    /**
     * A single top-level CONDITIONAL plan node nested {@code depth} levels deep via its own THEN
     * branch (a top-level conditional is depth 1, one nested in either branch is depth 2, matching
     * {@link RecordingV2PlanTreeValidator}'s own semantics) - its ELSE branch is always
     * structurally present but empty at every level, so only the THEN chain carries any depth.
     */
    static WorkflowExecutionPlan nestedConditionalPlan(String workflowId, int depth) {
        return new WorkflowExecutionPlan(
                new WorkflowId(workflowId), List.of(nestedConditionalPlanNode("c0", depth)));
    }

    static WorkflowPlanNode nestedConditionalPlanNode(String stepId, int depth) {
        List<WorkflowPlanNode> thenNodes =
                depth <= 1
                        ? List.of()
                        : List.of(nestedConditionalPlanNode(stepId + "c", depth - 1));
        return new WorkflowPlanNode(
                new WorkflowStepId(stepId),
                WorkflowStepType.CONDITIONAL,
                false,
                Optional.empty(),
                List.of(
                        new WorkflowPlanBranch(WorkflowBranchSelection.THEN, thenNodes),
                        new WorkflowPlanBranch(WorkflowBranchSelection.ELSE, List.of())));
    }

    /**
     * The execution-tree counterpart of {@link #nestedConditionalPlanNode}: a THEN selection at
     * every level down to {@code depth}, positionally aligned (identical step IDs) with its plan
     * counterpart.
     */
    static RecordedExecutionNodeV2 nestedConditionalExecutionNode(String stepId, int depth) {
        List<RecordedExecutionNodeV2> children =
                depth <= 1
                        ? List.of()
                        : List.of(nestedConditionalExecutionNode(stepId + "c", depth - 1));
        return new RecordedExecutionNodeV2(
                conditionalStep(stepId, true), Optional.of(WorkflowBranchSelection.THEN), children);
    }

    /**
     * A root {@code ifElse}-shaped CONDITIONAL plan node ("root") whose ELSE branch is empty and
     * whose THEN branch holds a conditional chain nested {@code thenDepth} levels deep - a genuine,
     * engine-producible shape for exercising the plan's own depth bound in a branch a real
     * execution selecting ELSE never enters. The root itself is depth 1, so the deepest node in the
     * THEN chain sits at overall depth {@code thenDepth + 1}.
     */
    static WorkflowExecutionPlan rootSelectsElseWithDeepThenPlan(String workflowId, int thenDepth) {
        WorkflowPlanNode deepThen = nestedConditionalPlanNode("deep", thenDepth);
        return new WorkflowExecutionPlan(
                new WorkflowId(workflowId),
                List.of(
                        new WorkflowPlanNode(
                                new WorkflowStepId("root"),
                                WorkflowStepType.CONDITIONAL,
                                false,
                                Optional.empty(),
                                List.of(
                                        new WorkflowPlanBranch(
                                                WorkflowBranchSelection.THEN, List.of(deepThen)),
                                        new WorkflowPlanBranch(
                                                WorkflowBranchSelection.ELSE, List.of())))));
    }

    /**
     * The execution-tree counterpart of {@link #rootSelectsElseWithDeepThenPlan}: a real,
     * engine-possible {@code false}-outcome {@code ELSE} decision with zero children, matching that
     * plan's empty {@code ELSE} branch - the deeply nested {@code THEN} branch is never entered,
     * exactly like a real execution's non-selected branch.
     */
    static RecordedExecutionNodeV2 rootSelectsElseNode() {
        return conditionalNode(
                conditionalStep("root", false), WorkflowBranchSelection.ELSE, List.of());
    }

    // ---- LOOP fixtures ----------------------------------------------------------------------

    static RecordedWorkflowStepV2 loopStep(String stepId) {
        return new RecordedWorkflowStepV2(
                new WorkflowStepId(stepId),
                WorkflowStepType.LOOP,
                WorkflowStepStatus.SUCCEEDED,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
    }

    static RecordedWorkflowStepV2 loopIterationStep(String stepId, boolean outcome) {
        return new RecordedWorkflowStepV2(
                new WorkflowStepId(stepId),
                WorkflowStepType.LOOP_ITERATION,
                WorkflowStepStatus.SUCCEEDED,
                Optional.of(new RecordedCondition(outcome, "d")),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
    }

    /** {@code LOOP_STEP_INTERRUPTED}: carries the interrupted iteration's own stepId. */
    static RecordedFailure loopStepInterruptedFailure(String stepId) {
        return new RecordedFailure(
                WorkflowFailureType.LOOP_STEP_INTERRUPTED,
                "safe message",
                Optional.of(new WorkflowStepId(stepId)),
                Optional.empty(),
                Optional.empty());
    }

    /** {@code LOOP_ITERATION_LIMIT_EXCEEDED}: carries the bound-exceeded iteration's own stepId. */
    static RecordedFailure loopIterationLimitExceededFailure(String stepId) {
        return new RecordedFailure(
                WorkflowFailureType.LOOP_ITERATION_LIMIT_EXCEEDED,
                "safe message",
                Optional.of(new WorkflowStepId(stepId)),
                Optional.empty(),
                Optional.empty());
    }

    /**
     * A {@code FAILED} {@code LOOP_ITERATION} step carrying {@code outcome} and {@code failure} -
     * for exercising the two {@code true}-outcome failure shapes {@code
     * WorkflowEngine.Session#executeLoopStepInto} can actually produce: {@link
     * io.webagent4j.workflow.WorkflowFailureType#LOOP_STEP_INTERRUPTED} (paired with a {@code THEN}
     * selection and zero children by the caller) and {@link
     * io.webagent4j.workflow.WorkflowFailureType#LOOP_ITERATION_LIMIT_EXCEEDED} (paired with no
     * selection at all).
     */
    static RecordedWorkflowStepV2 loopIterationStepFailed(
            String stepId, boolean outcome, RecordedFailure failure) {
        return new RecordedWorkflowStepV2(
                new WorkflowStepId(stepId),
                WorkflowStepType.LOOP_ITERATION,
                WorkflowStepStatus.FAILED,
                Optional.of(new RecordedCondition(outcome, "d")),
                Optional.empty(),
                Optional.of(failure),
                Optional.empty());
    }

    /** A {@code LOOP} plan node ("loopId") whose body is a single ACTION plan node ("bodyId"). */
    static WorkflowExecutionPlan loopPlan(String workflowId, String loopId, String bodyId) {
        return new WorkflowExecutionPlan(
                new WorkflowId(workflowId), List.of(loopPlanNode(loopId, bodyId)));
    }

    static WorkflowPlanNode loopPlanNode(String loopId, String bodyId) {
        return new WorkflowPlanNode(
                new WorkflowStepId(loopId),
                WorkflowStepType.LOOP,
                false,
                Optional.empty(),
                List.of(
                        new WorkflowPlanBranch(
                                WorkflowBranchSelection.THEN, List.of(actionPlanNode(bodyId)))));
    }

    /** A {@code LOOP} plan node ("loopId") whose body has two sequential ACTION plan nodes. */
    static WorkflowExecutionPlan loopPlanTwoBodySteps(
            String workflowId, String loopId, String bodyId1, String bodyId2) {
        return new WorkflowExecutionPlan(
                new WorkflowId(workflowId),
                List.of(
                        new WorkflowPlanNode(
                                new WorkflowStepId(loopId),
                                WorkflowStepType.LOOP,
                                false,
                                Optional.empty(),
                                List.of(
                                        new WorkflowPlanBranch(
                                                WorkflowBranchSelection.THEN,
                                                List.of(
                                                        actionPlanNode(bodyId1),
                                                        actionPlanNode(bodyId2)))))));
    }

    /**
     * A {@code LOOP} plan node ("loopId") with a genuinely empty declared body - a real, legal
     * shape ({@code WorkflowSteps.loop} does not require a non-empty body list) that a {@code
     * SUCCEEDED THEN} iteration with zero children must remain able to match.
     */
    static WorkflowExecutionPlan loopPlanEmptyBody(String workflowId, String loopId) {
        return new WorkflowExecutionPlan(
                new WorkflowId(workflowId),
                List.of(
                        new WorkflowPlanNode(
                                new WorkflowStepId(loopId),
                                WorkflowStepType.LOOP,
                                false,
                                Optional.empty(),
                                List.of(
                                        new WorkflowPlanBranch(
                                                WorkflowBranchSelection.THEN, List.of())))));
    }

    // ---- PARALLEL fixtures --------------------------------------------------------------------

    static RecordedWorkflowStepV2 parallelStep(String stepId) {
        return new RecordedWorkflowStepV2(
                new WorkflowStepId(stepId),
                WorkflowStepType.PARALLEL,
                WorkflowStepStatus.SUCCEEDED,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
    }

    static RecordedWorkflowStepV2 skippedParallelStep(String stepId, String description) {
        return new RecordedWorkflowStepV2(
                new WorkflowStepId(stepId),
                WorkflowStepType.PARALLEL,
                WorkflowStepStatus.SKIPPED,
                Optional.of(new RecordedCondition(false, description)),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
    }

    static RecordedWorkflowStepV2 parallelBranchStep(String stepId) {
        return new RecordedWorkflowStepV2(
                new WorkflowStepId(stepId),
                WorkflowStepType.PARALLEL_BRANCH,
                WorkflowStepStatus.SUCCEEDED,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
    }

    static RecordedWorkflowStepV2 notRunParallelBranchStep(String stepId) {
        return new RecordedWorkflowStepV2(
                new WorkflowStepId(stepId),
                WorkflowStepType.PARALLEL_BRANCH,
                WorkflowStepStatus.NOT_RUN,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
    }

    /** A {@code PARALLEL} plan node ("parallelId") whose branches are each a single ACTION step. */
    static WorkflowExecutionPlan parallelPlan(
            String workflowId, String parallelId, String... branchBodyIds) {
        return new WorkflowExecutionPlan(
                new WorkflowId(workflowId), List.of(parallelPlanNode(parallelId, branchBodyIds)));
    }

    static WorkflowPlanNode parallelPlanNode(String parallelId, String... branchBodyIds) {
        List<WorkflowPlanBranch> branches = new java.util.ArrayList<>();
        for (String bodyId : branchBodyIds) {
            branches.add(
                    new WorkflowPlanBranch(
                            WorkflowBranchSelection.THEN, List.of(actionPlanNode(bodyId))));
        }
        return new WorkflowPlanNode(
                new WorkflowStepId(parallelId),
                WorkflowStepType.PARALLEL,
                false,
                Optional.empty(),
                branches);
    }

    /**
     * The execution-tree counterpart of {@link #parallelPlanNode}: every branch launched and
     * SUCCEEDED, each with its own single ACTION step's real result.
     */
    static RecordedExecutionNodeV2 allBranchesSucceededNode(
            String parallelId, String... branchBodyIds) {
        List<RecordedExecutionNodeV2> branchNodes = new java.util.ArrayList<>();
        for (int i = 0; i < branchBodyIds.length; i++) {
            branchNodes.add(
                    new RecordedExecutionNodeV2(
                            parallelBranchStep(parallelId + "@" + i),
                            Optional.empty(),
                            List.of(
                                    leaf(
                                            succeededActionStep(
                                                    branchBodyIds[i] + "@" + i,
                                                    Optional.empty())))));
        }
        return new RecordedExecutionNodeV2(parallelStep(parallelId), Optional.empty(), branchNodes);
    }
}
