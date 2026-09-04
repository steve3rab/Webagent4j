package io.webagent4j.recording;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.webagent4j.action.ActionExecutionMode;
import io.webagent4j.action.ActionFailureType;
import io.webagent4j.action.ActionStatus;
import io.webagent4j.workflow.WorkflowBranchSelection;
import io.webagent4j.workflow.WorkflowFailureType;
import io.webagent4j.workflow.WorkflowId;
import io.webagent4j.workflow.WorkflowStatus;
import io.webagent4j.workflow.WorkflowStepId;
import io.webagent4j.workflow.WorkflowStepStatus;
import io.webagent4j.workflow.WorkflowStepType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * REC2-001..REC2-020-style coverage for {@link WorkflowRecordingV2}, {@link
 * RecordedExecutionNodeV2}, {@link RecordedWorkflowStepV2}, and {@link RecordingV2Invariants} - the
 * tree-shaped counterpart of {@link RecordingModelInvariantsTest}.
 */
class RecordingV2ModelInvariantsTest {

    /** REC2-001: a minimal COMPLETED recording round-trips its own accessors. */
    @Test
    void rec2001MinimalCompletedRecordingIsAccepted() {
        WorkflowRecordingV2 recording = RecordingV2Fixtures.minimalCompleted("wf");
        assertThat(recording.status()).isEqualTo(WorkflowStatus.COMPLETED);
        assertThat(recording.nodes()).hasSize(1);
    }

    /** REC2-002: {@code plan.workflowId()} must equal the recording's own {@code workflowId}. */
    @Test
    void rec2002PlanWorkflowIdMismatchIsRejected() {
        assertThatThrownBy(
                        () ->
                                new WorkflowRecordingV2(
                                        RecordingSchemaVersionV2.V2,
                                        new RecordingId("r1"),
                                        Instant.EPOCH,
                                        new WorkflowId("wf-a"),
                                        WorkflowStatus.COMPLETED,
                                        RecordingV2Fixtures.minimalPlan("wf-b"),
                                        List.of(
                                                RecordingV2Fixtures.leaf(
                                                        RecordingV2Fixtures.succeededAssignStep(
                                                                "step-1",
                                                                RecordingV2Fixtures.output(
                                                                        "output", "String",
                                                                        false)))),
                                        Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** REC2-003: a FAILED recording without a failure is rejected. */
    @Test
    void rec2003FailedRecordingWithoutFailureIsRejected() {
        assertThatThrownBy(
                        () ->
                                RecordingV2Fixtures.recordingWith(
                                        "wf",
                                        RecordingV2Fixtures.minimalPlan("wf"),
                                        WorkflowStatus.FAILED,
                                        List.of(
                                                RecordingV2Fixtures.leaf(
                                                        RecordingV2Fixtures.notRunStep("step-1"))),
                                        Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** REC2-004: a COMPLETED recording with a failure is rejected. */
    @Test
    void rec2004CompletedRecordingWithFailureIsRejected() {
        assertThatThrownBy(
                        () ->
                                RecordingV2Fixtures.recordingWith(
                                        "wf",
                                        RecordingV2Fixtures.minimalPlan("wf"),
                                        WorkflowStatus.COMPLETED,
                                        List.of(
                                                RecordingV2Fixtures.leaf(
                                                        RecordingV2Fixtures.succeededAssignStep(
                                                                "step-1",
                                                                RecordingV2Fixtures.output(
                                                                        "output", "String",
                                                                        false)))),
                                        Optional.of(
                                                RecordingFixtures.preflightFailure(
                                                        WorkflowFailureType
                                                                .MISSING_REQUIRED_INPUT))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** REC2-005: a recording with zero top-level nodes is rejected. */
    @Test
    void rec2005EmptyNodesIsRejected() {
        assertThatThrownBy(
                        () ->
                                RecordingV2Fixtures.recordingWith(
                                        "wf",
                                        RecordingV2Fixtures.minimalPlan("wf"),
                                        WorkflowStatus.COMPLETED,
                                        List.of(),
                                        Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** REC2-006: a non-CONDITIONAL step's node cannot carry a branch selection. */
    @Test
    void rec2006NonConditionalNodeWithBranchSelectionIsRejected() {
        RecordedWorkflowStepV2 step =
                RecordingV2Fixtures.succeededAssignStep(
                        "step-1", RecordingV2Fixtures.output("output", "String", false));
        assertThatThrownBy(
                        () ->
                                new RecordedExecutionNodeV2(
                                        step, Optional.of(WorkflowBranchSelection.THEN), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** REC2-007: a non-CONDITIONAL step's node cannot carry children. */
    @Test
    void rec2007NonConditionalNodeWithChildrenIsRejected() {
        RecordedWorkflowStepV2 step =
                RecordingV2Fixtures.succeededAssignStep(
                        "step-1", RecordingV2Fixtures.output("output", "String", false));
        RecordedExecutionNodeV2 child = RecordingV2Fixtures.leaf(step);
        assertThatThrownBy(
                        () -> new RecordedExecutionNodeV2(step, Optional.empty(), List.of(child)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** REC2-008: a CONDITIONAL node with children must carry a branch selection. */
    @Test
    void rec2008ConditionalNodeWithChildrenButNoSelectionIsRejected() {
        RecordedWorkflowStepV2 conditional = RecordingV2Fixtures.conditionalStep("cond-1", true);
        RecordedExecutionNodeV2 child =
                RecordingV2Fixtures.leaf(
                        RecordingV2Fixtures.succeededActionStep("then-1", Optional.empty()));
        assertThatThrownBy(
                        () ->
                                new RecordedExecutionNodeV2(
                                        conditional, Optional.empty(), List.of(child)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** REC2-009: a CONDITIONAL node with no children never needs a branch selection. */
    @Test
    void rec2009ConditionalNodeWithNoChildrenAndNoSelectionIsAccepted() {
        RecordedWorkflowStepV2 conditional = RecordingV2Fixtures.conditionalStep("cond-1", true);
        RecordedExecutionNodeV2 node =
                new RecordedExecutionNodeV2(conditional, Optional.empty(), List.of());
        assertThat(node.children()).isEmpty();
    }

    /** REC2-010: a valid THEN branch with nested children is accepted end to end. */
    @Test
    void rec2010ValidThenBranchRecordingIsAccepted() {
        RecordedWorkflowStepV2 conditional = RecordingV2Fixtures.conditionalStep("cond-1", true);
        RecordedWorkflowStepV2 thenStep =
                RecordingV2Fixtures.succeededActionStep("then-1", Optional.empty());
        RecordedExecutionNodeV2 conditionalNode =
                RecordingV2Fixtures.conditionalNode(
                        conditional,
                        WorkflowBranchSelection.THEN,
                        List.of(RecordingV2Fixtures.leaf(thenStep)));

        WorkflowRecordingV2 recording =
                RecordingV2Fixtures.recordingWith(
                        "wf",
                        RecordingV2Fixtures.branchingPlan("wf"),
                        WorkflowStatus.COMPLETED,
                        List.of(conditionalNode),
                        Optional.empty());

        assertThat(recording.nodes()).hasSize(1);
        assertThat(recording.nodes().get(0).children()).hasSize(1);
    }

    /**
     * REC2-011: the non-selected branch contributes zero nodes - duplicate step IDs across the tree
     * are still rejected.
     */
    @Test
    void rec2011DuplicateStepIdAcrossNestedNodesIsRejected() {
        RecordedWorkflowStepV2 conditional = RecordingV2Fixtures.conditionalStep("cond-1", true);
        RecordedWorkflowStepV2 duplicate =
                RecordingV2Fixtures.succeededActionStep("cond-1", Optional.empty());
        RecordedExecutionNodeV2 conditionalNode =
                RecordingV2Fixtures.conditionalNode(
                        conditional,
                        WorkflowBranchSelection.THEN,
                        List.of(RecordingV2Fixtures.leaf(duplicate)));

        assertThatThrownBy(
                        () ->
                                RecordingV2Fixtures.recordingWith(
                                        "wf",
                                        RecordingV2Fixtures.branchingPlan("wf"),
                                        WorkflowStatus.COMPLETED,
                                        List.of(conditionalNode),
                                        Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** REC2-012: a COMPLETED recording cannot contain a NOT_RUN node anywhere in the tree. */
    @Test
    void rec2012CompletedWithNestedNotRunNodeIsRejected() {
        RecordedWorkflowStepV2 conditional = RecordingV2Fixtures.conditionalStep("cond-1", true);
        RecordedExecutionNodeV2 conditionalNode =
                RecordingV2Fixtures.conditionalNode(
                        conditional,
                        WorkflowBranchSelection.THEN,
                        List.of(
                                RecordingV2Fixtures.leaf(
                                        RecordingV2Fixtures.notRunStep("then-1"))));

        assertThatThrownBy(
                        () ->
                                RecordingV2Fixtures.recordingWith(
                                        "wf",
                                        RecordingV2Fixtures.branchingPlan("wf"),
                                        WorkflowStatus.COMPLETED,
                                        List.of(conditionalNode),
                                        Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * REC2-013: a NOT_RUN step's node cannot carry a branch selection, even with empty children.
     */
    @Test
    void rec2013NotRunConditionalNodeWithSelectionIsRejected() {
        RecordedWorkflowStepV2 notRunConditional =
                RecordingV2Fixtures.notRunConditionalStep("cond-1");
        RecordedExecutionNodeV2 node =
                new RecordedExecutionNodeV2(
                        notRunConditional, Optional.of(WorkflowBranchSelection.NONE), List.of());

        assertThatThrownBy(
                        () ->
                                RecordingV2Fixtures.recordingWith(
                                        "wf",
                                        RecordingV2Fixtures.branchingPlan("wf"),
                                        WorkflowStatus.FAILED,
                                        List.of(node),
                                        Optional.of(
                                                RecordingFixtures.preflightFailure(
                                                        WorkflowFailureType
                                                                .MISSING_REQUIRED_INPUT))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** REC2-014: a NOT_RUN step's node cannot carry children, even without a selection. */
    @Test
    void rec2014NotRunNodeWithChildrenIsRejected() {
        RecordedWorkflowStepV2 notRunConditional =
                RecordingV2Fixtures.notRunConditionalStep("cond-1");
        RecordedExecutionNodeV2 phantomChild =
                RecordingV2Fixtures.leaf(
                        RecordingV2Fixtures.succeededActionStep("then-1", Optional.empty()));
        // Bypasses RecordedExecutionNodeV2's own CONDITIONAL-with-children-needs-selection check
        // by supplying a selection, so only RecordingV2Invariants' NOT_RUN-specific check can
        // catch this.
        RecordedExecutionNodeV2 node =
                new RecordedExecutionNodeV2(
                        notRunConditional,
                        Optional.of(WorkflowBranchSelection.THEN),
                        List.of(phantomChild));

        assertThatThrownBy(
                        () ->
                                RecordingV2Fixtures.recordingWith(
                                        "wf",
                                        RecordingV2Fixtures.branchingPlan("wf"),
                                        WorkflowStatus.FAILED,
                                        List.of(node),
                                        Optional.of(
                                                RecordingFixtures.preflightFailure(
                                                        WorkflowFailureType
                                                                .MISSING_REQUIRED_INPUT))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** REC2-015: a valid preflight failure (every top-level node NOT_RUN) is accepted. */
    @Test
    void rec2015ValidPreflightFailureIsAccepted() {
        WorkflowRecordingV2 recording =
                RecordingV2Fixtures.recordingWith(
                        "wf",
                        RecordingV2Fixtures.minimalPlan("wf"),
                        WorkflowStatus.FAILED,
                        List.of(RecordingV2Fixtures.leaf(RecordingV2Fixtures.notRunStep("step-1"))),
                        Optional.of(
                                RecordingFixtures.preflightFailure(
                                        WorkflowFailureType.MISSING_REQUIRED_INPUT)));

        assertThat(recording.nodes()).hasSize(1);
    }

    /**
     * REC2-016: a valid runtime failure inside a selected branch (conditional SUCCEEDED, its
     * selected THEN child FAILED) is accepted, with the flattened pre-order sequence driving the
     * fail-fast shape check exactly as {@code WorkflowEngine} would produce it.
     */
    @Test
    void rec2016ValidRuntimeFailureInsideSelectedBranchIsAccepted() {
        RecordedWorkflowStepV2 conditional = RecordingV2Fixtures.conditionalStep("cond-1", true);
        RecordedFailure failure =
                RecordingFixtures.actionFailedFailure("then-1", ActionFailureType.TARGET_NOT_FOUND);
        RecordedWorkflowStepV2 failedThen =
                RecordingV2Fixtures.actionStepFailedWithSummary(
                        "then-1",
                        failure,
                        ActionStatus.EXECUTION_FAILED,
                        ActionExecutionMode.NOT_EXECUTED);
        RecordedExecutionNodeV2 conditionalNode =
                RecordingV2Fixtures.conditionalNode(
                        conditional,
                        WorkflowBranchSelection.THEN,
                        List.of(RecordingV2Fixtures.leaf(failedThen)));

        WorkflowRecordingV2 recording =
                RecordingV2Fixtures.recordingWith(
                        "wf",
                        RecordingV2Fixtures.branchingPlan("wf"),
                        WorkflowStatus.FAILED,
                        List.of(conditionalNode),
                        Optional.of(failure));

        assertThat(recording.status()).isEqualTo(WorkflowStatus.FAILED);
    }

    /**
     * REC2-017: a step succeeding (in pre-order, i.e. after a selected branch returns) after the
     * FAILED step is rejected - top-level sibling following a branch that failed.
     */
    @Test
    void rec2017SuccessAfterFailedBranchIsRejected() {
        RecordedWorkflowStepV2 conditional = RecordingV2Fixtures.conditionalStep("cond-1", true);
        RecordedFailure failure =
                RecordingFixtures.actionFailedFailure("then-1", ActionFailureType.TARGET_NOT_FOUND);
        RecordedWorkflowStepV2 failedThen =
                RecordingV2Fixtures.actionStepFailedWithSummary(
                        "then-1",
                        failure,
                        ActionStatus.EXECUTION_FAILED,
                        ActionExecutionMode.NOT_EXECUTED);
        RecordedExecutionNodeV2 conditionalNode =
                RecordingV2Fixtures.conditionalNode(
                        conditional,
                        WorkflowBranchSelection.THEN,
                        List.of(RecordingV2Fixtures.leaf(failedThen)));
        RecordedExecutionNodeV2 trailingSuccess =
                RecordingV2Fixtures.leaf(
                        RecordingV2Fixtures.succeededAssignStep(
                                "after", RecordingV2Fixtures.output("o", "String", false)));

        assertThatThrownBy(
                        () ->
                                RecordingV2Fixtures.recordingWith(
                                        "wf",
                                        RecordingV2Fixtures.branchingPlan("wf"),
                                        WorkflowStatus.FAILED,
                                        List.of(conditionalNode, trailingSuccess),
                                        Optional.of(failure)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** REC2-018: two FAILED steps anywhere in the tree (one nested, one top-level) are rejected. */
    @Test
    void rec2018MultipleFailedStepsAcrossTreeAreRejected() {
        RecordedWorkflowStepV2 conditional = RecordingV2Fixtures.conditionalStep("cond-1", true);
        RecordedFailure innerFailure =
                RecordingFixtures.actionFailedFailure("then-1", ActionFailureType.TARGET_NOT_FOUND);
        RecordedWorkflowStepV2 failedThen =
                RecordingV2Fixtures.actionStepFailedWithSummary(
                        "then-1",
                        innerFailure,
                        ActionStatus.EXECUTION_FAILED,
                        ActionExecutionMode.NOT_EXECUTED);
        RecordedExecutionNodeV2 conditionalNode =
                RecordingV2Fixtures.conditionalNode(
                        conditional,
                        WorkflowBranchSelection.THEN,
                        List.of(RecordingV2Fixtures.leaf(failedThen)));
        RecordedFailure outerFailure =
                RecordingFixtures.actionFailedFailure("after", ActionFailureType.TARGET_NOT_FOUND);
        RecordedExecutionNodeV2 trailingFailed =
                RecordingV2Fixtures.leaf(
                        RecordingV2Fixtures.actionStepFailedWithSummary(
                                "after",
                                outerFailure,
                                ActionStatus.EXECUTION_FAILED,
                                ActionExecutionMode.NOT_EXECUTED));

        assertThatThrownBy(
                        () ->
                                RecordingV2Fixtures.recordingWith(
                                        "wf",
                                        RecordingV2Fixtures.branchingPlan("wf"),
                                        WorkflowStatus.FAILED,
                                        List.of(conditionalNode, trailingFailed),
                                        Optional.of(innerFailure)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * REC2-019: {@link RecordedWorkflowStepV2} rejects a SUCCEEDED ASSIGN step without an output.
     */
    @Test
    void rec2019SucceededAssignWithoutOutputIsRejected() {
        assertThatThrownBy(
                        () ->
                                new RecordedWorkflowStepV2(
                                        new WorkflowStepId("s1"),
                                        WorkflowStepType.ASSIGN,
                                        WorkflowStepStatus.SUCCEEDED,
                                        Optional.empty(),
                                        Optional.empty(),
                                        Optional.empty(),
                                        Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** REC2-020: a CONDITIONAL step's own recorded step can never carry a published output. */
    @Test
    void rec2020ConditionalStepWithOutputIsRejected() {
        assertThatThrownBy(
                        () ->
                                new RecordedWorkflowStepV2(
                                        new WorkflowStepId("cond-1"),
                                        WorkflowStepType.CONDITIONAL,
                                        WorkflowStepStatus.SUCCEEDED,
                                        Optional.of(new RecordedCondition(true, "d")),
                                        Optional.of(
                                                RecordingV2Fixtures.output("o", "String", false)),
                                        Optional.empty(),
                                        Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
