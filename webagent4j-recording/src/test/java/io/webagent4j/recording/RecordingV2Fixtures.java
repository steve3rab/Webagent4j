package io.webagent4j.recording;

import io.webagent4j.action.ActionExecutionMode;
import io.webagent4j.action.ActionStatus;
import io.webagent4j.action.ActionType;
import io.webagent4j.workflow.WorkflowBranchSelection;
import io.webagent4j.workflow.WorkflowExecutionPlan;
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
}
