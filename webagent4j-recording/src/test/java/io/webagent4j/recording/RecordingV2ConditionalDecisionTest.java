package io.webagent4j.recording;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.webagent4j.workflow.WorkflowBranchSelection;
import io.webagent4j.workflow.WorkflowExecutionPlan;
import io.webagent4j.workflow.WorkflowFailureType;
import io.webagent4j.workflow.WorkflowStatus;
import io.webagent4j.workflow.WorkflowStepId;
import io.webagent4j.workflow.WorkflowStepStatus;
import io.webagent4j.workflow.WorkflowStepType;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * DECISION-001..014 adversarial coverage for {@link
 * RecordingV2PlanTreeValidator#validateConditionalDecision}: proves a {@link
 * RecordedExecutionNodeV2} of type {@code CONDITIONAL} can never carry a decision state {@code
 * WorkflowEngine} could not actually have produced - see {@code
 * WorkflowEngine.Session#executeConditionalStepInto} for the ground truth this validator mirrors.
 *
 * <p>Every hand-built fixture here is a state a real execution can never reach; DECISION-012, 013,
 * and 014 instead lock the real, engine-possible states ({@code THEN}, {@code ELSE}, and {@code
 * NONE}) using a genuine {@code WorkflowEngine} execution captured by {@link WorkflowRecorderV2} -
 * see {@link WorkflowRecorderV2Test} for THEN/ELSE (already covered there) and {@code
 * WorkflowRecorderV2Test#capturesAnIfThenFalseDecisionAsNone} for NONE.
 *
 * <p>There is deliberately no "DECISION-015 guarded conditional SKIPPED" test: {@code
 * ConditionalWorkflowStep} does not support the generic {@code when(...)} guard other step types do
 * (its one condition slot already carries the mandatory branch-selector meaning - see {@code
 * ConditionalWorkflowStep}'s own Javadoc and {@code WorkflowBranchingBuilderTest} in {@code
 * webagent4j-workflow}), so a guarded, SKIPPED conditional is not a state the engine can ever
 * produce. Following that real contract rather than inventing one, DECISION-009 below locks the
 * actual, opposite guarantee: a CONDITIONAL step's own decision is never SKIPPED, full stop.
 */
class RecordingV2ConditionalDecisionTest {

    // ---- DECISION: a captured decision must be present together, or not at all ----

    /** DECISION-001: SUCCEEDED, condition outcome true, but no branch selection - rejected. */
    @Test
    void decision001SucceededTrueConditionWithNoSelectionIsRejected() {
        WorkflowExecutionPlan plan = RecordingV2Fixtures.branchingPlan("wf");
        RecordedExecutionNodeV2 node =
                new RecordedExecutionNodeV2(
                        RecordingV2Fixtures.conditionalStep("cond-1", true),
                        Optional.empty(),
                        List.of());

        assertThatThrownBy(
                        () ->
                                RecordingV2Fixtures.recordingWith(
                                        "wf",
                                        plan,
                                        WorkflowStatus.COMPLETED,
                                        List.of(node),
                                        Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be captured together or not at all");
    }

    /** DECISION-002: SUCCEEDED, condition outcome false, but no branch selection - rejected. */
    @Test
    void decision002SucceededFalseConditionWithNoSelectionIsRejected() {
        WorkflowExecutionPlan plan = RecordingV2Fixtures.branchingPlan("wf");
        RecordedExecutionNodeV2 node =
                new RecordedExecutionNodeV2(
                        RecordingV2Fixtures.conditionalStep("cond-1", false),
                        Optional.empty(),
                        List.of());

        assertThatThrownBy(
                        () ->
                                RecordingV2Fixtures.recordingWith(
                                        "wf",
                                        plan,
                                        WorkflowStatus.COMPLETED,
                                        List.of(node),
                                        Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be captured together or not at all");
    }

    // ---- DECISION: the selection must agree with the captured outcome ----

    /** DECISION-003: outcome true paired with ELSE (even though the plan has a real ELSE). */
    @Test
    void decision003TrueOutcomeWithElseSelectionIsRejected() {
        WorkflowExecutionPlan plan = RecordingV2Fixtures.branchingPlanWithElseStep("wf");
        RecordedExecutionNodeV2 node =
                RecordingV2Fixtures.conditionalNode(
                        RecordingV2Fixtures.conditionalStep("cond-1", true),
                        WorkflowBranchSelection.ELSE,
                        List.of());

        assertThatThrownBy(
                        () ->
                                RecordingV2Fixtures.recordingWith(
                                        "wf",
                                        plan,
                                        WorkflowStatus.COMPLETED,
                                        List.of(node),
                                        Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not consistent with the recorded condition outcome");
    }

    /** DECISION-004: outcome true paired with NONE (even though the plan has a real NONE). */
    @Test
    void decision004TrueOutcomeWithNoneSelectionIsRejected() {
        WorkflowExecutionPlan plan = RecordingV2Fixtures.ifThenPlan("wf");
        RecordedExecutionNodeV2 node =
                RecordingV2Fixtures.conditionalNode(
                        RecordingV2Fixtures.conditionalStep("cond-1", true),
                        WorkflowBranchSelection.NONE,
                        List.of());

        assertThatThrownBy(
                        () ->
                                RecordingV2Fixtures.recordingWith(
                                        "wf",
                                        plan,
                                        WorkflowStatus.COMPLETED,
                                        List.of(node),
                                        Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not consistent with the recorded condition outcome");
    }

    /** DECISION-005: outcome false paired with THEN. */
    @Test
    void decision005FalseOutcomeWithThenSelectionIsRejected() {
        WorkflowExecutionPlan plan = RecordingV2Fixtures.branchingPlan("wf");
        RecordedExecutionNodeV2 node =
                RecordingV2Fixtures.conditionalNode(
                        RecordingV2Fixtures.conditionalStep("cond-1", false),
                        WorkflowBranchSelection.THEN,
                        List.of());

        assertThatThrownBy(
                        () ->
                                RecordingV2Fixtures.recordingWith(
                                        "wf",
                                        plan,
                                        WorkflowStatus.COMPLETED,
                                        List.of(node),
                                        Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not consistent with the recorded condition outcome");
    }

    // ---- DECISION: the real, accepted engine states (hand-built here for isolation) ----

    /** DECISION-006: an {@code ifElse} deciding false and selecting the real ELSE branch. */
    @Test
    void decision006FalseOutcomeWithElseOnAnIfElseIsAccepted() {
        WorkflowExecutionPlan plan = RecordingV2Fixtures.branchingPlanWithElseStep("wf");
        RecordedExecutionNodeV2 node =
                RecordingV2Fixtures.conditionalNode(
                        RecordingV2Fixtures.conditionalStep("cond-1", false),
                        WorkflowBranchSelection.ELSE,
                        List.of(
                                RecordingV2Fixtures.leaf(
                                        RecordingV2Fixtures.succeededActionStep(
                                                "else-1", Optional.empty()))));

        WorkflowRecordingV2 recording =
                RecordingV2Fixtures.recordingWith(
                        "wf", plan, WorkflowStatus.COMPLETED, List.of(node), Optional.empty());

        assertThat(recording.nodes()).hasSize(1);
    }

    /** DECISION-007: an {@code ifThen} deciding false and selecting the structural NONE branch. */
    @Test
    void decision007FalseOutcomeWithNoneOnAnIfThenIsAccepted() {
        WorkflowExecutionPlan plan = RecordingV2Fixtures.ifThenPlan("wf");
        RecordedExecutionNodeV2 node =
                RecordingV2Fixtures.conditionalNode(
                        RecordingV2Fixtures.conditionalStep("cond-1", false),
                        WorkflowBranchSelection.NONE,
                        List.of());

        WorkflowRecordingV2 recording =
                RecordingV2Fixtures.recordingWith(
                        "wf", plan, WorkflowStatus.COMPLETED, List.of(node), Optional.empty());

        assertThat(recording.nodes()).hasSize(1);
    }

    /** DECISION-008: outcome true paired with THEN. */
    @Test
    void decision008TrueOutcomeWithThenIsAccepted() {
        WorkflowExecutionPlan plan = RecordingV2Fixtures.branchingPlan("wf");
        RecordedExecutionNodeV2 node =
                RecordingV2Fixtures.conditionalNode(
                        RecordingV2Fixtures.conditionalStep("cond-1", true),
                        WorkflowBranchSelection.THEN,
                        List.of(
                                RecordingV2Fixtures.leaf(
                                        RecordingV2Fixtures.succeededActionStep(
                                                "then-1", Optional.empty()))));

        WorkflowRecordingV2 recording =
                RecordingV2Fixtures.recordingWith(
                        "wf", plan, WorkflowStatus.COMPLETED, List.of(node), Optional.empty());

        assertThat(recording.nodes()).hasSize(1);
    }

    // ---- DECISION: statuses that never carry a decision ----

    /**
     * DECISION-009: a CONDITIONAL step's own decision is never {@code SKIPPED} - already enforced
     * by {@link RecordedWorkflowStepV2} itself (see this class's own Javadoc for why the engine can
     * never produce a guarded, SKIPPED conditional in the first place).
     */
    @Test
    void decision009SkippedConditionalIsRejected() {
        assertThatThrownBy(
                        () ->
                                new RecordedWorkflowStepV2(
                                        new WorkflowStepId("cond-1"),
                                        WorkflowStepType.CONDITIONAL,
                                        WorkflowStepStatus.SKIPPED,
                                        Optional.of(new RecordedCondition(false, "d")),
                                        Optional.empty(),
                                        Optional.empty(),
                                        Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("never SKIPPED");
    }

    /** DECISION-010: a NOT_RUN conditional cannot carry a branch selection. */
    @Test
    void decision010NotRunConditionalWithSelectionIsRejected() {
        WorkflowExecutionPlan plan = RecordingV2Fixtures.branchingPlan("wf");
        RecordedExecutionNodeV2 node =
                new RecordedExecutionNodeV2(
                        RecordingV2Fixtures.notRunConditionalStep("cond-1"),
                        Optional.of(WorkflowBranchSelection.THEN),
                        List.of());

        assertThatThrownBy(
                        () ->
                                RecordingV2Fixtures.recordingWith(
                                        "wf",
                                        plan,
                                        WorkflowStatus.FAILED,
                                        List.of(node),
                                        Optional.of(
                                                RecordingFixtures.preflightFailure(
                                                        WorkflowFailureType
                                                                .MISSING_REQUIRED_INPUT))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * DECISION-011: a SUCCEEDED conditional with no captured condition at all is rejected - a
     * succeeded decision is never made without capturing what it decided.
     */
    @Test
    void decision011SucceededConditionalWithoutConditionIsRejected() {
        WorkflowExecutionPlan plan = RecordingV2Fixtures.branchingPlan("wf");
        RecordedExecutionNodeV2 node =
                new RecordedExecutionNodeV2(
                        RecordingV2Fixtures.conditionalStepWithoutCondition("cond-1"),
                        Optional.empty(),
                        List.of());

        assertThatThrownBy(
                        () ->
                                RecordingV2Fixtures.recordingWith(
                                        "wf",
                                        plan,
                                        WorkflowStatus.COMPLETED,
                                        List.of(node),
                                        Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must carry the branch decision it captured");
    }
}
