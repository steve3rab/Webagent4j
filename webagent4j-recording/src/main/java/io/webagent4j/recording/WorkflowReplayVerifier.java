package io.webagent4j.recording;

import io.webagent4j.workflow.WorkflowActionSummary;
import io.webagent4j.workflow.WorkflowConditionResult;
import io.webagent4j.workflow.WorkflowFailure;
import io.webagent4j.workflow.WorkflowResult;
import io.webagent4j.workflow.WorkflowStepId;
import io.webagent4j.workflow.WorkflowStepResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Deterministically compares a {@link WorkflowRecording} against a new, independently produced
 * {@code WorkflowResult}.
 *
 * <p>This is pure structured comparison, never re-execution: {@link #verify} invokes no browser, no
 * backend, and no {@code WorkflowEngine} - it never performs an action, and it is the caller's
 * responsibility to have already obtained {@code actual} from a real new execution. Comparison
 * never fails fast: every mismatch is collected in one deterministic left-to-right traversal
 * (workflow identity and status, then step count, then each common step index in declaration order,
 * then any missing or extra trailing steps, then the top-level failure), so a single verify call
 * reports every difference at once.
 *
 * <p>{@link RecordingId}, {@code capturedAt}, {@code ActionId}, a condition's description text, a
 * failure's {@code safeMessage}, and a failure's underlying exception type name are deliberately
 * never compared: they are trace or diagnostic metadata that can legitimately differ between two
 * semantically identical executions (a fresh random correlation ID, an embedded timestamp, and so
 * on), not part of a workflow's documented behavior.
 */
public final class WorkflowReplayVerifier {

    private static final String ABSENT = "<absent>";

    /** Creates a verifier. Stateless: a single instance may verify any number of results. */
    public WorkflowReplayVerifier() {}

    /** Compares {@code expected} against {@code actual}, collecting every mismatch found. */
    public WorkflowReplayResult verify(WorkflowRecording expected, WorkflowResult actual) {
        Objects.requireNonNull(expected, "expected");
        Objects.requireNonNull(actual, "actual");
        List<WorkflowReplayMismatch> mismatches = new ArrayList<>();
        compareText(
                mismatches,
                WorkflowReplayMismatchType.WORKFLOW_ID_MISMATCH,
                "$.workflow.workflowId",
                expected.workflowId().value(),
                actual.workflowId().value());
        compareText(
                mismatches,
                WorkflowReplayMismatchType.WORKFLOW_STATUS_MISMATCH,
                "$.workflow.status",
                expected.status().name(),
                actual.status().name());
        compareSteps(mismatches, expected.steps(), actual.steps());
        compareFailure(mismatches, "$.failure", expected.failure(), actual.failure());
        return new WorkflowReplayResult(expected.recordingId(), mismatches);
    }

    private static void compareSteps(
            List<WorkflowReplayMismatch> mismatches,
            List<RecordedWorkflowStep> expected,
            List<WorkflowStepResult> actual) {
        if (expected.size() != actual.size()) {
            mismatches.add(
                    new WorkflowReplayMismatch(
                            WorkflowReplayMismatchType.STEP_COUNT_MISMATCH,
                            "$.workflow.steps",
                            String.valueOf(expected.size()),
                            String.valueOf(actual.size())));
        }
        int common = Math.min(expected.size(), actual.size());
        for (int i = 0; i < common; i++) {
            compareStep(mismatches, i, expected.get(i), actual.get(i));
        }
        for (int i = common; i < expected.size(); i++) {
            mismatches.add(
                    new WorkflowReplayMismatch(
                            WorkflowReplayMismatchType.MISSING_STEP,
                            "$.workflow.steps[" + i + "]",
                            expected.get(i).stepId().value(),
                            ABSENT));
        }
        for (int i = common; i < actual.size(); i++) {
            mismatches.add(
                    new WorkflowReplayMismatch(
                            WorkflowReplayMismatchType.EXTRA_STEP,
                            "$.workflow.steps[" + i + "]",
                            ABSENT,
                            actual.get(i).stepId().value()));
        }
    }

    private static void compareStep(
            List<WorkflowReplayMismatch> mismatches,
            int index,
            RecordedWorkflowStep expected,
            WorkflowStepResult actual) {
        String path = "$.workflow.steps[" + index + "]";
        compareText(
                mismatches,
                WorkflowReplayMismatchType.STEP_ID_MISMATCH,
                path + ".stepId",
                expected.stepId().value(),
                actual.stepId().value());
        compareText(
                mismatches,
                WorkflowReplayMismatchType.STEP_TYPE_MISMATCH,
                path + ".stepType",
                expected.stepType().name(),
                actual.stepType().name());
        compareText(
                mismatches,
                WorkflowReplayMismatchType.STEP_STATUS_MISMATCH,
                path + ".status",
                expected.status().name(),
                actual.status().name());
        compareCondition(mismatches, path + ".condition", expected.condition(), actual.condition());
        compareOutputVariable(
                mismatches,
                path + ".outputVariableName",
                expected.outputVariableName(),
                actual.outputVariableName());
        compareAction(mismatches, path + ".action", expected.action(), actual.actionSummary());
        compareFailure(mismatches, path + ".failure", expected.failure(), actual.failure());
    }

    private static void compareCondition(
            List<WorkflowReplayMismatch> mismatches,
            String path,
            Optional<RecordedCondition> expected,
            Optional<WorkflowConditionResult> actual) {
        if (expected.isPresent() != actual.isPresent()) {
            mismatches.add(
                    new WorkflowReplayMismatch(
                            WorkflowReplayMismatchType.CONDITION_PRESENCE_MISMATCH,
                            path,
                            presenceText(expected.isPresent()),
                            presenceText(actual.isPresent())));
            return;
        }
        if (expected.isEmpty()) {
            return;
        }
        // description is diagnostic text and is never compared.
        compareText(
                mismatches,
                WorkflowReplayMismatchType.CONDITION_OUTCOME_MISMATCH,
                path + ".outcome",
                String.valueOf(expected.get().outcome()),
                String.valueOf(actual.get().outcome()));
    }

    private static void compareOutputVariable(
            List<WorkflowReplayMismatch> mismatches,
            String path,
            Optional<String> expected,
            Optional<String> actual) {
        if (!Objects.equals(expected, actual)) {
            mismatches.add(
                    new WorkflowReplayMismatch(
                            WorkflowReplayMismatchType.OUTPUT_VARIABLE_MISMATCH,
                            path,
                            expected.orElse(ABSENT),
                            actual.orElse(ABSENT)));
        }
    }

    private static void compareAction(
            List<WorkflowReplayMismatch> mismatches,
            String path,
            Optional<RecordedAction> expected,
            Optional<WorkflowActionSummary> actual) {
        if (expected.isPresent() != actual.isPresent()) {
            mismatches.add(
                    new WorkflowReplayMismatch(
                            WorkflowReplayMismatchType.ACTION_PRESENCE_MISMATCH,
                            path,
                            presenceText(expected.isPresent()),
                            presenceText(actual.isPresent())));
            return;
        }
        if (expected.isEmpty()) {
            return;
        }
        // actionId is a fresh correlation identifier per execution and is never compared.
        RecordedAction expectedAction = expected.get();
        WorkflowActionSummary actualAction = actual.get();
        compareText(
                mismatches,
                WorkflowReplayMismatchType.ACTION_TYPE_MISMATCH,
                path + ".actionType",
                expectedAction.actionType().name(),
                actualAction.actionType().name());
        compareText(
                mismatches,
                WorkflowReplayMismatchType.ACTION_STATUS_MISMATCH,
                path + ".status",
                expectedAction.status().name(),
                actualAction.status().name());
        compareText(
                mismatches,
                WorkflowReplayMismatchType.ACTION_EXECUTION_MODE_MISMATCH,
                path + ".executionMode",
                expectedAction.executionMode().name(),
                actualAction.executionMode().name());
    }

    private static void compareFailure(
            List<WorkflowReplayMismatch> mismatches,
            String path,
            Optional<RecordedFailure> expected,
            Optional<WorkflowFailure> actual) {
        if (expected.isPresent() != actual.isPresent()) {
            mismatches.add(
                    new WorkflowReplayMismatch(
                            WorkflowReplayMismatchType.FAILURE_PRESENCE_MISMATCH,
                            path,
                            presenceText(expected.isPresent()),
                            presenceText(actual.isPresent())));
            return;
        }
        if (expected.isEmpty()) {
            return;
        }
        // safeMessage and underlyingTypeName are diagnostic text and are never compared.
        RecordedFailure expectedFailure = expected.get();
        WorkflowFailure actualFailure = actual.get();
        compareText(
                mismatches,
                WorkflowReplayMismatchType.FAILURE_TYPE_MISMATCH,
                path + ".type",
                expectedFailure.type().name(),
                actualFailure.type().name());
        compareText(
                mismatches,
                WorkflowReplayMismatchType.FAILURE_STEP_ID_MISMATCH,
                path + ".stepId",
                expectedFailure.stepId().map(WorkflowStepId::value).orElse(ABSENT),
                actualFailure.stepId().map(WorkflowStepId::value).orElse(ABSENT));
        compareText(
                mismatches,
                WorkflowReplayMismatchType.ACTION_FAILURE_TYPE_MISMATCH,
                path + ".actionFailureType",
                expectedFailure.actionFailureType().map(Enum::name).orElse(ABSENT),
                actualFailure.actionFailureType().map(Enum::name).orElse(ABSENT));
    }

    private static void compareText(
            List<WorkflowReplayMismatch> mismatches,
            WorkflowReplayMismatchType type,
            String path,
            String expected,
            String actual) {
        if (!Objects.equals(expected, actual)) {
            mismatches.add(new WorkflowReplayMismatch(type, path, expected, actual));
        }
    }

    private static String presenceText(boolean present) {
        return present ? "present" : "absent";
    }
}
