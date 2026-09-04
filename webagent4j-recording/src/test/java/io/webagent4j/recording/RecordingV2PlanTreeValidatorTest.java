package io.webagent4j.recording;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.webagent4j.workflow.WorkflowBranchSelection;
import io.webagent4j.workflow.WorkflowExecutionPlan;
import io.webagent4j.workflow.WorkflowId;
import io.webagent4j.workflow.WorkflowPlanNode;
import io.webagent4j.workflow.WorkflowPlanOutput;
import io.webagent4j.workflow.WorkflowStatus;
import io.webagent4j.workflow.WorkflowStepId;
import io.webagent4j.workflow.WorkflowStepType;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * PLAN-TREE-001..012 adversarial coverage for {@link RecordingV2PlanTreeValidator}: proves a {@link
 * WorkflowRecordingV2} can never be constructed unless its {@link RecordedExecutionNodeV2} tree is
 * a genuine, structurally authorized path through its own {@link WorkflowExecutionPlan}.
 *
 * <p>Every adversarial fixture here is hand-built directly - never produced through {@link
 * WorkflowRecorderV2} - so each one demonstrates a plan/tree pair a real execution could never
 * produce, exactly the hostile-input shape {@link RecordingV2PlanTreeValidator} exists to reject.
 *
 * <p>PLAN-TREE-012 (the non-selected branch contributes zero entries to a replay) is a
 * Deterministic Replay behavior, not a construction-time rejection, and is already covered end to
 * end - using a genuine {@code WorkflowEngine} execution - by {@code
 * WorkflowReplayerTest#rplReplay002SelectedThenBranchIsReplayedAndElseIsAbsent}.
 */
class RecordingV2PlanTreeValidatorTest {

    // ---- PLAN-TREE: step identity and type ----

    /** PLAN-TREE-001: the plan's step ID and the recorded tree's step ID must match exactly. */
    @Test
    void planTree001MismatchedTopLevelStepIdIsRejected() {
        WorkflowExecutionPlan plan = RecordingV2Fixtures.minimalPlan("wf");
        List<RecordedExecutionNodeV2> nodes =
                List.of(
                        RecordingV2Fixtures.leaf(
                                RecordingV2Fixtures.succeededAssignStep(
                                        "step-2",
                                        RecordingV2Fixtures.output("output", "String", false))));

        assertThatThrownBy(
                        () ->
                                RecordingV2Fixtures.recordingWith(
                                        "wf",
                                        plan,
                                        WorkflowStatus.COMPLETED,
                                        nodes,
                                        Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("recorded step ID does not match");
    }

    /** PLAN-TREE-002: the same step ID with a different step type is rejected. */
    @Test
    void planTree002MismatchedStepTypeIsRejected() {
        WorkflowExecutionPlan plan = RecordingV2Fixtures.minimalPlan("wf");
        List<RecordedExecutionNodeV2> nodes =
                List.of(
                        RecordingV2Fixtures.leaf(
                                RecordingV2Fixtures.succeededActionStep(
                                        "step-1", Optional.empty())));

        assertThatThrownBy(
                        () ->
                                RecordingV2Fixtures.recordingWith(
                                        "wf",
                                        plan,
                                        WorkflowStatus.COMPLETED,
                                        nodes,
                                        Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("recorded step type does not match");
    }

    // ---- PLAN-TREE: branch/children correspondence ----

    /**
     * PLAN-TREE-003: a THEN selection whose recorded child actually belongs to the ELSE branch is
     * rejected - branch content can never be swapped under a mismatched announced selection.
     */
    @Test
    void planTree003ThenSelectionWithElseChildIsRejected() {
        WorkflowExecutionPlan plan = RecordingV2Fixtures.branchingPlanWithElseStep("wf");
        RecordedExecutionNodeV2 conditionalNode =
                RecordingV2Fixtures.conditionalNode(
                        RecordingV2Fixtures.conditionalStep("cond-1", true),
                        WorkflowBranchSelection.THEN,
                        List.of(
                                RecordingV2Fixtures.leaf(
                                        RecordingV2Fixtures.succeededActionStep(
                                                "else-1", Optional.empty()))));

        assertThatThrownBy(
                        () ->
                                RecordingV2Fixtures.recordingWith(
                                        "wf",
                                        plan,
                                        WorkflowStatus.COMPLETED,
                                        List.of(conditionalNode),
                                        Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("recorded step ID does not match");
    }

    /** PLAN-TREE-004: an extra child beyond the selected branch's own plan nodes is rejected. */
    @Test
    void planTree004ExtraChildNotInSelectedBranchIsRejected() {
        WorkflowExecutionPlan plan = RecordingV2Fixtures.branchingPlan("wf");
        RecordedExecutionNodeV2 conditionalNode =
                RecordingV2Fixtures.conditionalNode(
                        RecordingV2Fixtures.conditionalStep("cond-1", true),
                        WorkflowBranchSelection.THEN,
                        List.of(
                                RecordingV2Fixtures.leaf(
                                        RecordingV2Fixtures.succeededActionStep(
                                                "then-1", Optional.empty())),
                                RecordingV2Fixtures.leaf(
                                        RecordingV2Fixtures.succeededActionStep(
                                                "extra-1", Optional.empty()))));

        assertThatThrownBy(
                        () ->
                                RecordingV2Fixtures.recordingWith(
                                        "wf",
                                        plan,
                                        WorkflowStatus.COMPLETED,
                                        List.of(conditionalNode),
                                        Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("do not match the recorded plan's node count");
    }

    /** PLAN-TREE-005: a missing required node in an otherwise-COMPLETED path is rejected. */
    @Test
    void planTree005MissingRequiredNodeInCompletedPathIsRejected() {
        WorkflowExecutionPlan plan = RecordingV2Fixtures.twoStepThenBranchPlan("wf");
        RecordedExecutionNodeV2 conditionalNode =
                RecordingV2Fixtures.conditionalNode(
                        RecordingV2Fixtures.conditionalStep("cond-1", true),
                        WorkflowBranchSelection.THEN,
                        List.of(
                                RecordingV2Fixtures.leaf(
                                        RecordingV2Fixtures.succeededActionStep(
                                                "then-1", Optional.empty()))));

        assertThatThrownBy(
                        () ->
                                RecordingV2Fixtures.recordingWith(
                                        "wf",
                                        plan,
                                        WorkflowStatus.COMPLETED,
                                        List.of(conditionalNode),
                                        Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("do not match the recorded plan's node count");
    }

    /** PLAN-TREE-006: NONE is rejected when the plan has no structurally possible NONE branch. */
    @Test
    void planTree006NoneSelectionWithoutAStructuralNoneBranchIsRejected() {
        WorkflowExecutionPlan plan = RecordingV2Fixtures.branchingPlan("wf");
        RecordedExecutionNodeV2 conditionalNode =
                RecordingV2Fixtures.conditionalNode(
                        RecordingV2Fixtures.conditionalStep("cond-1", false),
                        WorkflowBranchSelection.NONE,
                        List.of());

        assertThatThrownBy(
                        () ->
                                RecordingV2Fixtures.recordingWith(
                                        "wf",
                                        plan,
                                        WorkflowStatus.COMPLETED,
                                        List.of(conditionalNode),
                                        Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not structurally possible");
    }

    /** PLAN-TREE-007: ELSE is rejected on an {@code ifThen} plan node that declares no ELSE. */
    @Test
    void planTree007ElseSelectionOnIfThenWithoutARealElseIsRejected() {
        WorkflowExecutionPlan plan = RecordingV2Fixtures.ifThenPlan("wf");
        RecordedExecutionNodeV2 conditionalNode =
                RecordingV2Fixtures.conditionalNode(
                        RecordingV2Fixtures.conditionalStep("cond-1", false),
                        WorkflowBranchSelection.ELSE,
                        List.of());

        assertThatThrownBy(
                        () ->
                                RecordingV2Fixtures.recordingWith(
                                        "wf",
                                        plan,
                                        WorkflowStatus.COMPLETED,
                                        List.of(conditionalNode),
                                        Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not structurally possible");
    }

    // ---- PLAN-TREE: declared output fidelity ----

    /** PLAN-TREE-008a: a recorded output whose name differs from the plan's declaration. */
    @Test
    void planTree008aWrongOutputNameIsRejected() {
        assertOutputMismatchRejected(
                RecordingV2Fixtures.output("output", "String", false),
                RecordingV2Fixtures.output("different-name", "String", false));
    }

    /** PLAN-TREE-008b: a recorded output whose declared type differs from the plan's. */
    @Test
    void planTree008bWrongOutputTypeIsRejected() {
        assertOutputMismatchRejected(
                RecordingV2Fixtures.output("output", "String", false),
                RecordingV2Fixtures.output("output", "Integer", false));
    }

    /**
     * PLAN-TREE-008c: a recorded output whose secret classification differs from the plan's - a
     * SECRET declaration can never be silently downgraded to PUBLIC, or vice versa.
     */
    @Test
    void planTree008cWrongOutputSecretClassificationIsRejected() {
        assertOutputMismatchRejected(
                RecordingV2Fixtures.output("output", "String", true),
                RecordingV2Fixtures.output("output", "String", false));
    }

    private static void assertOutputMismatchRejected(
            WorkflowPlanOutput declared, WorkflowPlanOutput recorded) {
        WorkflowExecutionPlan plan =
                new WorkflowExecutionPlan(
                        new WorkflowId("wf"),
                        List.of(
                                new WorkflowPlanNode(
                                        new WorkflowStepId("step-1"),
                                        WorkflowStepType.ASSIGN,
                                        false,
                                        Optional.of(declared),
                                        List.of())));
        List<RecordedExecutionNodeV2> nodes =
                List.of(
                        RecordingV2Fixtures.leaf(
                                RecordingV2Fixtures.succeededAssignStep("step-1", recorded)));

        assertThatThrownBy(
                        () ->
                                RecordingV2Fixtures.recordingWith(
                                        "wf",
                                        plan,
                                        WorkflowStatus.COMPLETED,
                                        nodes,
                                        Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("recorded output does not match");
    }

    // ---- PLAN-TREE: positive/coherent cases ----

    /** PLAN-TREE-009: a coherent plan/tree pair is accepted. */
    @Test
    void planTree009CoherentPlanAndTreeIsAccepted() {
        WorkflowExecutionPlan plan = RecordingV2Fixtures.branchingPlan("wf");
        RecordedExecutionNodeV2 conditionalNode =
                RecordingV2Fixtures.conditionalNode(
                        RecordingV2Fixtures.conditionalStep("cond-1", true),
                        WorkflowBranchSelection.THEN,
                        List.of(
                                RecordingV2Fixtures.leaf(
                                        RecordingV2Fixtures.succeededActionStep(
                                                "then-1", Optional.empty()))));

        WorkflowRecordingV2 recording =
                RecordingV2Fixtures.recordingWith(
                        "wf",
                        plan,
                        WorkflowStatus.COMPLETED,
                        List.of(conditionalNode),
                        Optional.empty());

        assertThat(recording.nodes()).hasSize(1);
    }

    /** PLAN-TREE-010: a valid, correctly nested conditional recurses and is accepted. */
    @Test
    void planTree010ValidNestedConditionalIsAccepted() {
        WorkflowExecutionPlan plan = RecordingV2Fixtures.nestedConditionalPlan("wf", 3);
        List<RecordedExecutionNodeV2> nodes =
                List.of(RecordingV2Fixtures.nestedConditionalExecutionNode("c0", 3));

        WorkflowRecordingV2 recording =
                RecordingV2Fixtures.recordingWith(
                        "wf", plan, WorkflowStatus.COMPLETED, nodes, Optional.empty());

        assertThat(recording.nodes()).hasSize(1);
    }

    /**
     * PLAN-TREE-011: a nested conditional corrupted only at its deepest level is still rejected -
     * the recursive check does not stop short before reaching the corruption.
     */
    @Test
    void planTree011DeeplyNestedCorruptionIsRejected() {
        WorkflowExecutionPlan plan = RecordingV2Fixtures.nestedConditionalPlan("wf", 3);
        // The innermost recorded node's stepId is wrong ("bogus" instead of "c0cc") - two levels
        // of otherwise-valid nesting sit above it.
        RecordedExecutionNodeV2 corruptedInnermost =
                new RecordedExecutionNodeV2(
                        RecordingV2Fixtures.conditionalStep("bogus", true),
                        Optional.of(WorkflowBranchSelection.THEN),
                        List.of());
        RecordedExecutionNodeV2 middle =
                new RecordedExecutionNodeV2(
                        RecordingV2Fixtures.conditionalStep("c0c", true),
                        Optional.of(WorkflowBranchSelection.THEN),
                        List.of(corruptedInnermost));
        RecordedExecutionNodeV2 top =
                new RecordedExecutionNodeV2(
                        RecordingV2Fixtures.conditionalStep("c0", true),
                        Optional.of(WorkflowBranchSelection.THEN),
                        List.of(middle));

        assertThatThrownBy(
                        () ->
                                RecordingV2Fixtures.recordingWith(
                                        "wf",
                                        plan,
                                        WorkflowStatus.COMPLETED,
                                        List.of(top),
                                        Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("recorded step ID does not match");
    }
}
