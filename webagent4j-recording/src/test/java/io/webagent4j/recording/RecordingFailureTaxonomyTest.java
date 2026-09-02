package io.webagent4j.recording;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.webagent4j.action.ActionExecutionMode;
import io.webagent4j.action.ActionFailureType;
import io.webagent4j.action.ActionStatus;
import io.webagent4j.action.ActionType;
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
 * Derives every Round 2 validation rule from the exact {@code WorkflowEngine} failure-state matrix
 * (see {@link RecordedFailure}, {@link RecordedWorkflowStep}, and {@link RecordingInvariants}
 * Javadoc): which {@code WorkflowFailureType} values are preflight vs. runtime, which step type
 * each runtime type can occur on, which action-summary shape each carries, and the full
 * field-for-field equality now required between a recording's overall failure and its FAILED step's
 * own failure.
 */
class RecordingFailureTaxonomyTest {

    private static WorkflowRecording recordingWith(
            WorkflowStatus status,
            List<RecordedWorkflowStep> steps,
            Optional<RecordedFailure> failure) {
        return new WorkflowRecording(
                RecordingSchemaVersion.V1,
                new RecordingId("r1"),
                Instant.EPOCH,
                new WorkflowId("wf"),
                status,
                steps,
                failure);
    }

    // ==================== INV-FAIL-PREFLIGHT ====================

    /** INV-FAIL-PREFLIGHT-001: MISSING_REQUIRED_INPUT with all-NOT_RUN steps is accepted. */
    @Test
    void invFailPreflight001MissingRequiredInputAllNotRunAccepted() {
        RecordedFailure failure =
                RecordingFixtures.preflightFailure(WorkflowFailureType.MISSING_REQUIRED_INPUT);
        WorkflowRecording recording =
                recordingWith(
                        WorkflowStatus.FAILED,
                        List.of(RecordingFixtures.notRunStep("s1")),
                        Optional.of(failure));
        assertThat(recording.failure()).contains(failure);
    }

    /** INV-FAIL-PREFLIGHT-002: INPUT_TYPE_MISMATCH with all-NOT_RUN steps is accepted. */
    @Test
    void invFailPreflight002InputTypeMismatchAllNotRunAccepted() {
        RecordedFailure failure =
                RecordingFixtures.preflightFailure(WorkflowFailureType.INPUT_TYPE_MISMATCH);
        WorkflowRecording recording =
                recordingWith(
                        WorkflowStatus.FAILED,
                        List.of(RecordingFixtures.notRunStep("s1")),
                        Optional.of(failure));
        assertThat(recording.failure()).contains(failure);
    }

    /** INV-FAIL-PREFLIGHT-003: UNDECLARED_INPUT with all-NOT_RUN steps is accepted. */
    @Test
    void invFailPreflight003UndeclaredInputAllNotRunAccepted() {
        RecordedFailure failure =
                RecordingFixtures.preflightFailure(WorkflowFailureType.UNDECLARED_INPUT);
        WorkflowRecording recording =
                recordingWith(
                        WorkflowStatus.FAILED,
                        List.of(RecordingFixtures.notRunStep("s1")),
                        Optional.of(failure));
        assertThat(recording.failure()).contains(failure);
    }

    /** INV-FAIL-PREFLIGHT-004: a preflight failure carrying a stepId is rejected. */
    @Test
    void invFailPreflight004PreflightFailureWithStepIdIsRejected() {
        RecordedFailure failure =
                new RecordedFailure(
                        WorkflowFailureType.MISSING_REQUIRED_INPUT,
                        "safe message",
                        Optional.of(new WorkflowStepId("s1")),
                        Optional.empty(),
                        Optional.empty());
        assertThatThrownBy(
                        () ->
                                recordingWith(
                                        WorkflowStatus.FAILED,
                                        List.of(RecordingFixtures.notRunStep("s1")),
                                        Optional.of(failure)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * INV-FAIL-PREFLIGHT-005: a preflight failure carrying an underlying exception type is
     * rejected.
     */
    @Test
    void invFailPreflight005PreflightFailureWithUnderlyingTypeNameIsRejected() {
        RecordedFailure failure =
                new RecordedFailure(
                        WorkflowFailureType.MISSING_REQUIRED_INPUT,
                        "safe message",
                        Optional.empty(),
                        Optional.of("java.lang.RuntimeException"),
                        Optional.empty());
        assertThatThrownBy(
                        () ->
                                recordingWith(
                                        WorkflowStatus.FAILED,
                                        List.of(RecordingFixtures.notRunStep("s1")),
                                        Optional.of(failure)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * INV-FAIL-PREFLIGHT-006: a preflight failure type can never carry an ActionFailureType at all
     * - rejected already at {@link RecordedFailure}'s own construction.
     */
    @Test
    void invFailPreflight006PreflightFailureWithActionFailureTypeIsRejected() {
        assertThatThrownBy(
                        () ->
                                new RecordedFailure(
                                        WorkflowFailureType.MISSING_REQUIRED_INPUT,
                                        "safe message",
                                        Optional.empty(),
                                        Optional.empty(),
                                        Optional.of(ActionFailureType.TARGET_NOT_FOUND)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * INV-FAIL-PREFLIGHT-007 (the core Round-2 fix): a non-preflight failure type with no stepId -
     * previously wrongly accepted whenever every step happened to be NOT_RUN - is now rejected:
     * only the three preflight types may omit a stepId.
     */
    @Test
    void invFailPreflight007RuntimeFailureTypeWithNoStepIdIsRejected() {
        RecordedFailure failure =
                new RecordedFailure(
                        WorkflowFailureType.ACTION_FAILED,
                        "safe message",
                        Optional.empty(),
                        Optional.empty(),
                        Optional.of(ActionFailureType.TARGET_NOT_FOUND));
        assertThatThrownBy(
                        () ->
                                recordingWith(
                                        WorkflowStatus.FAILED,
                                        List.of(RecordingFixtures.notRunStep("s1")),
                                        Optional.of(failure)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** INV-FAIL-PREFLIGHT-008: a preflight failure with a non-NOT_RUN step present is rejected. */
    @Test
    void invFailPreflight008PreflightFailureWithNonNotRunStepIsRejected() {
        RecordedFailure failure =
                RecordingFixtures.preflightFailure(WorkflowFailureType.MISSING_REQUIRED_INPUT);
        assertThatThrownBy(
                        () ->
                                recordingWith(
                                        WorkflowStatus.FAILED,
                                        List.of(RecordingFixtures.succeededAssignStep("s1", "o1")),
                                        Optional.of(failure)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ==================== INV-FAIL-COHERENCE ====================

    /** INV-FAIL-COHERENCE-001: overall/step failure differing only in safeMessage is rejected. */
    @Test
    void invFailCoherence001SafeMessageMismatchIsRejected() {
        RecordedFailure stepFailure =
                RecordingFixtures.actionFailedFailure("s1", ActionFailureType.TARGET_NOT_FOUND);
        RecordedFailure overallFailure =
                new RecordedFailure(
                        WorkflowFailureType.ACTION_FAILED,
                        "a different safe message",
                        Optional.of(new WorkflowStepId("s1")),
                        Optional.empty(),
                        Optional.of(ActionFailureType.TARGET_NOT_FOUND));
        List<RecordedWorkflowStep> steps =
                List.of(
                        RecordingFixtures.actionStepFailedWithSummary(
                                "s1", stepFailure, ActionStatus.EXECUTION_FAILED));
        assertThatThrownBy(
                        () ->
                                recordingWith(
                                        WorkflowStatus.FAILED, steps, Optional.of(overallFailure)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * INV-FAIL-COHERENCE-002: overall/step failure differing only in underlyingTypeName is
     * rejected.
     */
    @Test
    void invFailCoherence002UnderlyingTypeNameMismatchIsRejected() {
        RecordedFailure stepFailure = RecordingFixtures.stepExceptionFailure("s1");
        RecordedFailure overallFailure =
                new RecordedFailure(
                        WorkflowFailureType.STEP_EXCEPTION,
                        stepFailure.safeMessage(),
                        stepFailure.stepId(),
                        Optional.of("java.lang.IllegalStateException"),
                        Optional.empty());
        List<RecordedWorkflowStep> steps =
                List.of(RecordingFixtures.actionStepFailedNoSummary("s1", stepFailure));
        assertThatThrownBy(
                        () ->
                                recordingWith(
                                        WorkflowStatus.FAILED, steps, Optional.of(overallFailure)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * INV-FAIL-COHERENCE-003: overall/step failure differing only in actionFailureType is rejected.
     */
    @Test
    void invFailCoherence003ActionFailureTypeMismatchIsRejected() {
        RecordedFailure stepFailure =
                RecordingFixtures.actionFailedFailure("s1", ActionFailureType.TARGET_NOT_FOUND);
        RecordedFailure overallFailure =
                RecordingFixtures.actionFailedFailure("s1", ActionFailureType.TARGET_AMBIGUOUS);
        List<RecordedWorkflowStep> steps =
                List.of(
                        RecordingFixtures.actionStepFailedWithSummary(
                                "s1", stepFailure, ActionStatus.EXECUTION_FAILED));
        assertThatThrownBy(
                        () ->
                                recordingWith(
                                        WorkflowStatus.FAILED, steps, Optional.of(overallFailure)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * INV-FAIL-COHERENCE-004: a fully identical overall/step failure with a valid surrounding trace
     * is accepted.
     */
    @Test
    void invFailCoherence004FullyIdenticalFailureIsAccepted() {
        RecordedFailure failure =
                RecordingFixtures.actionFailedFailure("s2", ActionFailureType.TARGET_NOT_FOUND);
        List<RecordedWorkflowStep> steps =
                List.of(
                        RecordingFixtures.succeededAssignStep("s1", "o1"),
                        RecordingFixtures.actionStepFailedWithSummary(
                                "s2", failure, ActionStatus.EXECUTION_FAILED),
                        RecordingFixtures.notRunStep("s3"));
        WorkflowRecording recording =
                recordingWith(WorkflowStatus.FAILED, steps, Optional.of(failure));
        assertThat(recording.steps()).hasSize(3);
    }

    /** INV-FAIL-COHERENCE-005: a runtime failure's stepId matching no FAILED step is rejected. */
    @Test
    void invFailCoherence005FailureStepIdWithNoFailedStepIsRejected() {
        RecordedFailure failure =
                RecordingFixtures.actionFailedFailure("s1", ActionFailureType.TARGET_NOT_FOUND);
        List<RecordedWorkflowStep> steps =
                List.of(RecordingFixtures.succeededAssignStep("s1", "o1"));
        assertThatThrownBy(() -> recordingWith(WorkflowStatus.FAILED, steps, Optional.of(failure)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * INV-FAIL-COHERENCE-006: a non-NOT_RUN step two positions after the FAILED step is rejected.
     */
    @Test
    void invFailCoherence006NonNotRunStepAfterFailedStepIsRejected() {
        RecordedFailure failure =
                RecordingFixtures.actionFailedFailure("s1", ActionFailureType.TARGET_NOT_FOUND);
        List<RecordedWorkflowStep> steps =
                List.of(
                        RecordingFixtures.actionStepFailedWithSummary(
                                "s1", failure, ActionStatus.EXECUTION_FAILED),
                        RecordingFixtures.notRunStep("s2"),
                        RecordingFixtures.skippedStep("s3", false, "d"));
        assertThatThrownBy(() -> recordingWith(WorkflowStatus.FAILED, steps, Optional.of(failure)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ==================== INV-FAIL-ACTION ====================

    /**
     * INV-FAIL-ACTION-001: CONDITION_EVALUATION_FAILED with an action summary present is rejected.
     */
    @Test
    void invFailAction001ConditionEvaluationFailedWithActionSummaryIsRejected() {
        RecordedFailure failure = RecordingFixtures.conditionEvaluationFailedFailure("s1");
        assertThatThrownBy(
                        () ->
                                RecordingFixtures.actionStepFailedWithSummary(
                                        "s1", failure, ActionStatus.SUCCESS))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** INV-FAIL-ACTION-002: MISSING_VARIABLE on an ASSIGN step is rejected (ACTION-only). */
    @Test
    void invFailAction002MissingVariableOnAssignStepIsRejected() {
        RecordedFailure failure = RecordingFixtures.missingVariableFailure("s1");
        assertThatThrownBy(() -> RecordingFixtures.assignStepFailedNoSummary("s1", failure))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** INV-FAIL-ACTION-003: ACTION_FACTORY_FAILED with an action summary present is rejected. */
    @Test
    void invFailAction003ActionFactoryFailedWithActionSummaryIsRejected() {
        RecordedFailure failure = RecordingFixtures.actionFactoryFailedFailure("s1");
        assertThatThrownBy(
                        () ->
                                RecordingFixtures.actionStepFailedWithSummary(
                                        "s1", failure, ActionStatus.SUCCESS))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** INV-FAIL-ACTION-004: ACTION_FAILED without an action summary is rejected. */
    @Test
    void invFailAction004ActionFailedWithoutSummaryIsRejected() {
        RecordedFailure failure =
                RecordingFixtures.actionFailedFailure("s1", ActionFailureType.TARGET_NOT_FOUND);
        assertThatThrownBy(() -> RecordingFixtures.actionStepFailedNoSummary("s1", failure))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** INV-FAIL-ACTION-005: ACTION_FAILED with a SUCCESS-status action summary is rejected. */
    @Test
    void invFailAction005ActionFailedWithSuccessStatusSummaryIsRejected() {
        RecordedFailure failure =
                RecordingFixtures.actionFailedFailure("s1", ActionFailureType.TARGET_NOT_FOUND);
        assertThatThrownBy(
                        () ->
                                RecordingFixtures.actionStepFailedWithSummary(
                                        "s1", failure, ActionStatus.SUCCESS))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** INV-FAIL-ACTION-006: NULL_OUTPUT without an action summary is rejected. */
    @Test
    void invFailAction006NullOutputWithoutSummaryIsRejected() {
        RecordedFailure failure = RecordingFixtures.nullOutputFailure("s1");
        assertThatThrownBy(() -> RecordingFixtures.actionStepFailedNoSummary("s1", failure))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ==================== INV-TAX ====================

    /** INV-TAX-001: ACTION_FAILED without an ActionFailureType is rejected. */
    @Test
    void invTax001ActionFailedWithoutActionFailureTypeIsRejected() {
        assertThatThrownBy(
                        () ->
                                new RecordedFailure(
                                        WorkflowFailureType.ACTION_FAILED,
                                        "safe message",
                                        Optional.of(new WorkflowStepId("s1")),
                                        Optional.empty(),
                                        Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** INV-TAX-002: a non-ACTION_FAILED type carrying an ActionFailureType is rejected. */
    @Test
    void invTax002NonActionFailedWithActionFailureTypeIsRejected() {
        assertThatThrownBy(
                        () ->
                                new RecordedFailure(
                                        WorkflowFailureType.STEP_EXCEPTION,
                                        "safe message",
                                        Optional.of(new WorkflowStepId("s1")),
                                        Optional.empty(),
                                        Optional.of(ActionFailureType.TARGET_NOT_FOUND)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** INV-TAX-003: ACTION_FAILED with an ActionFailureType present is accepted. */
    @Test
    void invTax003ActionFailedWithActionFailureTypeIsAccepted() {
        RecordedFailure failure =
                RecordingFixtures.actionFailedFailure("s1", ActionFailureType.TARGET_NOT_FOUND);
        assertThat(failure.actionFailureType()).contains(ActionFailureType.TARGET_NOT_FOUND);
    }

    /** INV-TAX-004: every preflight type without an ActionFailureType is accepted. */
    @Test
    void invTax004EveryPreflightTypeWithoutActionFailureTypeIsAccepted() {
        for (WorkflowFailureType type :
                List.of(
                        WorkflowFailureType.MISSING_REQUIRED_INPUT,
                        WorkflowFailureType.INPUT_TYPE_MISMATCH,
                        WorkflowFailureType.UNDECLARED_INPUT)) {
            RecordedFailure failure = RecordingFixtures.preflightFailure(type);
            assertThat(failure.actionFailureType()).isEmpty();
        }
    }

    /**
     * INV-TAX-005: a FAILED step whose own failure.stepId disagrees with the step's stepId is
     * rejected.
     */
    @Test
    void invTax005FailedStepFailureStepIdMismatchIsRejected() {
        RecordedFailure failure =
                RecordingFixtures.actionFailedFailure("other", ActionFailureType.TARGET_NOT_FOUND);
        assertThatThrownBy(
                        () ->
                                RecordingFixtures.actionStepFailedWithSummary(
                                        "s1", failure, ActionStatus.EXECUTION_FAILED))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** INV-TAX-006: a FAILED step whose own failure carries no stepId at all is rejected. */
    @Test
    void invTax006FailedStepFailureWithEmptyStepIdIsRejected() {
        RecordedFailure failure =
                new RecordedFailure(
                        WorkflowFailureType.ACTION_FAILED,
                        "safe message",
                        Optional.empty(),
                        Optional.empty(),
                        Optional.of(ActionFailureType.TARGET_NOT_FOUND));
        assertThatThrownBy(
                        () ->
                                RecordingFixtures.actionStepFailedWithSummary(
                                        "s1", failure, ActionStatus.EXECUTION_FAILED))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** INV-TAX-007: a FAILED step whose own failure.stepId matches its stepId is accepted. */
    @Test
    void invTax007FailedStepFailureStepIdMatchIsAccepted() {
        RecordedFailure failure =
                RecordingFixtures.actionFailedFailure("s1", ActionFailureType.TARGET_NOT_FOUND);
        RecordedWorkflowStep step =
                RecordingFixtures.actionStepFailedWithSummary(
                        "s1", failure, ActionStatus.EXECUTION_FAILED);
        assertThat(step.failure()).contains(failure);
    }

    /**
     * INV-TAX-008: every non-preflight failure type is individually constructible as a
     * properly-shaped FAILED step - this is the strongest proof the taxonomy is not over-tight.
     */
    @Test
    void invTax008EveryRuntimeFailureTypeIsIndividuallyValidAsFailedStep() {
        assertThat(
                        RecordingFixtures.actionStepFailedNoSummary(
                                        "s1",
                                        RecordingFixtures.conditionEvaluationFailedFailure("s1"))
                                .status())
                .isEqualTo(WorkflowStepStatus.FAILED);
        assertThat(
                        RecordingFixtures.actionStepFailedNoSummary(
                                        "s1", RecordingFixtures.missingVariableFailure("s1"))
                                .status())
                .isEqualTo(WorkflowStepStatus.FAILED);
        assertThat(
                        RecordingFixtures.actionStepFailedNoSummary(
                                        "s1", RecordingFixtures.actionFactoryFailedFailure("s1"))
                                .status())
                .isEqualTo(WorkflowStepStatus.FAILED);
        assertThat(
                        RecordingFixtures.actionStepFailedNoSummary(
                                        "s1", RecordingFixtures.stepExceptionFailure("s1"))
                                .status())
                .isEqualTo(WorkflowStepStatus.FAILED);
        assertThat(
                        RecordingFixtures.actionStepFailedWithSummary(
                                        "s1",
                                        RecordingFixtures.actionFailedFailure(
                                                "s1", ActionFailureType.TARGET_NOT_FOUND),
                                        ActionStatus.EXECUTION_FAILED)
                                .status())
                .isEqualTo(WorkflowStepStatus.FAILED);
        assertThat(
                        RecordingFixtures.actionStepFailedWithSummary(
                                        "s1",
                                        RecordingFixtures.nullOutputFailure("s1"),
                                        ActionStatus.SUCCESS)
                                .status())
                .isEqualTo(WorkflowStepStatus.FAILED);
        assertThat(
                        RecordingFixtures.actionStepFailedWithSummary(
                                        "s1",
                                        RecordingFixtures.outputTypeMismatchFailure("s1"),
                                        ActionStatus.SUCCESS)
                                .status())
                .isEqualTo(WorkflowStepStatus.FAILED);
    }

    /** INV-TAX-009: a preflight failure type used as a step's own failure is rejected. */
    @Test
    void invTax009PreflightFailureTypeAsStepOwnFailureIsRejected() {
        RecordedFailure failure =
                new RecordedFailure(
                        WorkflowFailureType.MISSING_REQUIRED_INPUT,
                        "safe message",
                        Optional.of(new WorkflowStepId("s1")),
                        Optional.empty(),
                        Optional.empty());
        assertThatThrownBy(() -> RecordingFixtures.actionStepFailedNoSummary("s1", failure))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ==================== INV-ACTION ====================

    /** INV-ACTION-001: MISSING_VARIABLE on ASSIGN is rejected (ACTION-only). */
    @Test
    void invAction001MissingVariableOnAssignIsRejected() {
        assertThatThrownBy(
                        () ->
                                RecordingFixtures.assignStepFailedNoSummary(
                                        "s1", RecordingFixtures.missingVariableFailure("s1")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** INV-ACTION-002: ACTION_FACTORY_FAILED on ASSIGN is rejected (ACTION-only). */
    @Test
    void invAction002ActionFactoryFailedOnAssignIsRejected() {
        assertThatThrownBy(
                        () ->
                                RecordingFixtures.assignStepFailedNoSummary(
                                        "s1", RecordingFixtures.actionFactoryFailedFailure("s1")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** INV-ACTION-003: STEP_EXCEPTION on ASSIGN is rejected (ACTION-only). */
    @Test
    void invAction003StepExceptionOnAssignIsRejected() {
        assertThatThrownBy(
                        () ->
                                RecordingFixtures.assignStepFailedNoSummary(
                                        "s1", RecordingFixtures.stepExceptionFailure("s1")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** INV-ACTION-004: ACTION_FAILED on ASSIGN is rejected (ACTION-only). */
    @Test
    void invAction004ActionFailedOnAssignIsRejected() {
        assertThatThrownBy(
                        () ->
                                new RecordedWorkflowStep(
                                        new WorkflowStepId("s1"),
                                        WorkflowStepType.ASSIGN,
                                        WorkflowStepStatus.FAILED,
                                        Optional.empty(),
                                        Optional.empty(),
                                        Optional.of(
                                                RecordingFixtures.actionFailedFailure(
                                                        "s1", ActionFailureType.TARGET_NOT_FOUND)),
                                        Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** INV-ACTION-005: NULL_OUTPUT on ASSIGN is rejected (ACTION-only). */
    @Test
    void invAction005NullOutputOnAssignIsRejected() {
        assertThatThrownBy(
                        () ->
                                RecordingFixtures.assignStepFailedNoSummary(
                                        "s1", RecordingFixtures.nullOutputFailure("s1")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** INV-ACTION-006: OUTPUT_TYPE_MISMATCH on ASSIGN is rejected (ACTION-only). */
    @Test
    void invAction006OutputTypeMismatchOnAssignIsRejected() {
        assertThatThrownBy(
                        () ->
                                RecordingFixtures.assignStepFailedNoSummary(
                                        "s1", RecordingFixtures.outputTypeMismatchFailure("s1")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * INV-ACTION-007: CONDITION_EVALUATION_FAILED on ASSIGN is accepted - the one runtime failure
     * type an ASSIGN step can carry ({@code AssignWorkflowStep#run} never fails on its own).
     */
    @Test
    void invAction007ConditionEvaluationFailedOnAssignIsAccepted() {
        RecordedWorkflowStep step =
                RecordingFixtures.assignStepFailedNoSummary(
                        "s1", RecordingFixtures.conditionEvaluationFailedFailure("s1"));
        assertThat(step.stepType()).isEqualTo(WorkflowStepType.ASSIGN);
    }

    /** INV-ACTION-008: CONDITION_EVALUATION_FAILED on ACTION is accepted. */
    @Test
    void invAction008ConditionEvaluationFailedOnActionIsAccepted() {
        RecordedWorkflowStep step =
                RecordingFixtures.actionStepFailedNoSummary(
                        "s1", RecordingFixtures.conditionEvaluationFailedFailure("s1"));
        assertThat(step.stepType()).isEqualTo(WorkflowStepType.ACTION);
    }

    /**
     * INV-ACTION-009: a SUCCEEDED ACTION step's action summary reporting a non-SUCCESS status is
     * rejected.
     */
    @Test
    void invAction009SucceededActionWithNonSuccessSummaryIsRejected() {
        assertThatThrownBy(
                        () ->
                                new RecordedWorkflowStep(
                                        new WorkflowStepId("s1"),
                                        WorkflowStepType.ACTION,
                                        WorkflowStepStatus.SUCCEEDED,
                                        Optional.empty(),
                                        Optional.empty(),
                                        Optional.empty(),
                                        Optional.of(
                                                RecordingFixtures.action(
                                                        ActionType.CLICK,
                                                        ActionStatus.EXECUTION_FAILED,
                                                        ActionExecutionMode.REAL))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** INV-ACTION-010: a SUCCEEDED ACTION step without any action summary is rejected. */
    @Test
    void invAction010SucceededActionWithoutSummaryIsRejected() {
        assertThatThrownBy(
                        () ->
                                new RecordedWorkflowStep(
                                        new WorkflowStepId("s1"),
                                        WorkflowStepType.ACTION,
                                        WorkflowStepStatus.SUCCEEDED,
                                        Optional.empty(),
                                        Optional.empty(),
                                        Optional.empty(),
                                        Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** INV-ACTION-011: ACTION_FAILED preserves the exact ActionResult outcome matrix. */
    @Test
    void invAction011ActionFailedEnforcesCompleteActionOutcomeMatrix() {
        for (ActionStatus status : ActionStatus.values()) {
            for (ActionExecutionMode executionMode : ActionExecutionMode.values()) {
                for (ActionFailureType failureType : ActionFailureType.values()) {
                    RecordedFailure failure =
                            RecordingFixtures.actionFailedFailure("s1", failureType);
                    Runnable construction =
                            () ->
                                    RecordingFixtures.actionStepFailedWithSummary(
                                            "s1", failure, status, executionMode);
                    String description = status + "/" + executionMode + "/" + failureType;
                    if (isValidActionFailureOutcome(status, executionMode, failureType)) {
                        assertThatCode(construction::run)
                                .as(description)
                                .doesNotThrowAnyException();
                    } else {
                        assertThatThrownBy(construction::run)
                                .as(description)
                                .isInstanceOf(IllegalArgumentException.class);
                    }
                }
            }
        }
    }

    private static boolean isValidActionFailureOutcome(
            ActionStatus status, ActionExecutionMode executionMode, ActionFailureType failureType) {
        return switch (status) {
            case PRECONDITION_FAILED ->
                    executionMode == ActionExecutionMode.NOT_EXECUTED
                            && failureType == ActionFailureType.PRECONDITION_FAILED;
            case EXECUTION_FAILED ->
                    switch (executionMode) {
                        case NOT_EXECUTED ->
                                failureType == ActionFailureType.TARGET_NOT_FOUND
                                        || failureType == ActionFailureType.TARGET_AMBIGUOUS
                                        || failureType == ActionFailureType.BACKEND_FAILURE
                                        || failureType == ActionFailureType.TARGET_CHANGED
                                        || failureType == ActionFailureType.POLICY_DENIED
                                        || failureType
                                                == ActionFailureType.POLICY_EVALUATION_FAILED;
                        case REAL ->
                                failureType == ActionFailureType.TARGET_NOT_INTERACTABLE
                                        || failureType
                                                == ActionFailureType.ACTION_NOT_SUPPORTED_BY_TARGET
                                        || failureType == ActionFailureType.BACKEND_FAILURE
                                        || failureType == ActionFailureType.UPLOAD_FAILURE
                                        || failureType == ActionFailureType.DOWNLOAD_FAILURE
                                        || failureType == ActionFailureType.POLICY_VIOLATION
                                        || failureType == ActionFailureType.STABILIZATION_FAILED;
                        case DRY_RUN -> false;
                    };
            case VERIFICATION_FAILED ->
                    executionMode == ActionExecutionMode.REAL
                            && failureType == ActionFailureType.POSTCONDITION_FAILED;
            case TIMEOUT ->
                    (executionMode == ActionExecutionMode.REAL
                                    || executionMode == ActionExecutionMode.NOT_EXECUTED)
                            && failureType == ActionFailureType.TIMEOUT;
            case CANCELLED ->
                    (executionMode == ActionExecutionMode.REAL
                                    || executionMode == ActionExecutionMode.NOT_EXECUTED)
                            && failureType == ActionFailureType.INTERRUPTED;
            case SUCCESS -> false;
        };
    }

    // ==================== INV-ASSIGN ====================

    /**
     * INV-ASSIGN-001: a SUCCEEDED ASSIGN step without a published output variable name is rejected.
     */
    @Test
    void invAssign001SucceededAssignWithoutOutputVariableIsRejected() {
        assertThatThrownBy(
                        () ->
                                new RecordedWorkflowStep(
                                        new WorkflowStepId("s1"),
                                        WorkflowStepType.ASSIGN,
                                        WorkflowStepStatus.SUCCEEDED,
                                        Optional.empty(),
                                        Optional.empty(),
                                        Optional.empty(),
                                        Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * INV-ASSIGN-002: a SUCCEEDED ASSIGN step with a published output variable name is accepted.
     */
    @Test
    void invAssign002SucceededAssignWithOutputVariableIsAccepted() {
        RecordedWorkflowStep step = RecordingFixtures.succeededAssignStep("s1", "out1");
        assertThat(step.outputVariableName()).contains("out1");
    }

    /**
     * INV-ASSIGN-003: an ASSIGN step carrying an action, even alongside a legitimate ASSIGN failure
     * type, is rejected.
     */
    @Test
    void invAssign003AssignStepWithActionIsRejected() {
        assertThatThrownBy(
                        () ->
                                new RecordedWorkflowStep(
                                        new WorkflowStepId("s1"),
                                        WorkflowStepType.ASSIGN,
                                        WorkflowStepStatus.FAILED,
                                        Optional.empty(),
                                        Optional.empty(),
                                        Optional.of(
                                                RecordingFixtures.conditionEvaluationFailedFailure(
                                                        "s1")),
                                        Optional.of(
                                                RecordingFixtures.action(
                                                        ActionType.CLICK,
                                                        ActionStatus.SUCCESS,
                                                        ActionExecutionMode.REAL))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** INV-ASSIGN-004: a SKIPPED ASSIGN step (guard false) is accepted, like an ACTION step. */
    @Test
    void invAssign004SkippedAssignIsAccepted() {
        RecordedWorkflowStep step = RecordingFixtures.skippedAssignStep("s1", false, "d");
        assertThat(step.status()).isEqualTo(WorkflowStepStatus.SKIPPED);
    }

    /** INV-ASSIGN-005: a NOT_RUN ASSIGN step is accepted. */
    @Test
    void invAssign005NotRunAssignIsAccepted() {
        RecordedWorkflowStep step = RecordingFixtures.notRunAssignStep("s1");
        assertThat(step.status()).isEqualTo(WorkflowStepStatus.NOT_RUN);
    }

    /**
     * INV-ASSIGN-006: a FAILED ASSIGN step (CONDITION_EVALUATION_FAILED) needs no output variable.
     */
    @Test
    void invAssign006FailedAssignNeedsNoOutputVariable() {
        RecordedWorkflowStep step =
                RecordingFixtures.assignStepFailedNoSummary(
                        "s1", RecordingFixtures.conditionEvaluationFailedFailure("s1"));
        assertThat(step.outputVariableName()).isEmpty();
    }
}
