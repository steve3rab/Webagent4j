package io.webagent4j.recording;

import io.webagent4j.workflow.WorkflowFailureType;
import io.webagent4j.workflow.WorkflowStatus;
import io.webagent4j.workflow.WorkflowStepId;
import io.webagent4j.workflow.WorkflowStepStatus;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Global, cross-step invariants for {@link WorkflowRecording}, factored out of its compact
 * constructor so the identical checks apply whether a recording is built directly, produced by
 * {@link WorkflowRecorder}, or decoded by {@link JsonWorkflowRecordingCodec} - there is no separate
 * construction path that could bypass them.
 *
 * <p>These invariants encode that {@code WorkflowEngine} is sequential and fail-fast (see {@code
 * docs/recording.md}): a recording that could never result from a real execution - two FAILED
 * steps, a SUCCEEDED step after a FAILED one, a duplicate step ID, a COMPLETED recording containing
 * a FAILED or NOT_RUN step - is rejected here, not merely accepted as inert data.
 *
 * <p>{@code WorkflowEngine} produces exactly two shapes for a {@code FAILED} recording,
 * distinguished by whether {@code failure.type()} is one of the three <em>preflight</em> categories
 * ({@code MISSING_REQUIRED_INPUT}, {@code INPUT_TYPE_MISMATCH}, {@code UNDECLARED_INPUT}) that
 * {@code WorkflowEngine.Session#validateAndSeedInputs} raises before step 0 ever runs: a preflight
 * failure always carries no {@code stepId}, no {@code underlyingTypeName}, and no {@code
 * ActionFailureType} (see {@code WorkflowEngine.Session#failBeforeExecution}), with every step
 * {@code NOT_RUN}; every other failure type is a <em>runtime</em> failure that always carries the
 * failing step's {@code stepId}. The overall failure and the FAILED step's own failure are required
 * to be fully identical for a runtime failure: {@code WorkflowEngine.Session#run} assigns the exact
 * same {@code WorkflowFailure} instance to both the terminal {@code WorkflowResult} and the failing
 * step's own {@code WorkflowStepResult} (see {@code Session#run} and {@code Session#failedResult}),
 * and {@link WorkflowRecorder} projects both from that one source - so within a single recording
 * they can never legitimately differ, even in {@code safeMessage} or {@code underlyingTypeName}.
 * This is distinct from {@link WorkflowReplayVerifier}, which deliberately ignores those same two
 * fields when comparing failures <em>across two different executions</em>.
 */
final class RecordingInvariants {

    private static final Set<WorkflowFailureType> PREFLIGHT_FAILURE_TYPES =
            EnumSet.of(
                    WorkflowFailureType.MISSING_REQUIRED_INPUT,
                    WorkflowFailureType.INPUT_TYPE_MISMATCH,
                    WorkflowFailureType.UNDECLARED_INPUT);

    private RecordingInvariants() {}

    static void validate(
            WorkflowStatus status,
            List<RecordedWorkflowStep> steps,
            Optional<RecordedFailure> failure) {
        if (steps.isEmpty()) {
            throw new IllegalArgumentException("a recording must contain at least one step");
        }
        requireUniqueStepIds(steps);
        if (status == WorkflowStatus.COMPLETED) {
            requireOnlySucceededOrSkipped(steps);
        } else {
            requireValidFailedTrace(steps, failure.orElseThrow());
        }
    }

    private static void requireUniqueStepIds(List<RecordedWorkflowStep> steps) {
        Set<WorkflowStepId> seen = new HashSet<>();
        for (RecordedWorkflowStep step : steps) {
            if (!seen.add(step.stepId())) {
                throw new IllegalArgumentException("a recording cannot contain duplicate step IDs");
            }
        }
    }

    private static void requireOnlySucceededOrSkipped(List<RecordedWorkflowStep> steps) {
        for (RecordedWorkflowStep step : steps) {
            if (step.status() != WorkflowStepStatus.SUCCEEDED
                    && step.status() != WorkflowStepStatus.SKIPPED) {
                throw new IllegalArgumentException(
                        "a COMPLETED recording's steps must all be SUCCEEDED or SKIPPED");
            }
        }
    }

    private static void requireValidFailedTrace(
            List<RecordedWorkflowStep> steps, RecordedFailure failure) {
        if (PREFLIGHT_FAILURE_TYPES.contains(failure.type())) {
            requirePreflightShape(steps, failure);
        } else {
            requireRuntimeShape(steps, failure);
        }
    }

    private static void requirePreflightShape(
            List<RecordedWorkflowStep> steps, RecordedFailure failure) {
        if (failure.stepId().isPresent()) {
            throw new IllegalArgumentException("a preflight failure cannot carry a stepId");
        }
        if (failure.underlyingTypeName().isPresent()) {
            throw new IllegalArgumentException(
                    "a preflight failure cannot carry an underlying exception type name");
        }
        if (failure.actionFailureType().isPresent()) {
            throw new IllegalArgumentException(
                    "a preflight failure cannot carry an ActionFailureType");
        }
        requireAllNotRun(steps);
    }

    private static void requireRuntimeShape(
            List<RecordedWorkflowStep> steps, RecordedFailure failure) {
        if (failure.stepId().isEmpty()) {
            throw new IllegalArgumentException(
                    "a non-preflight failure type must carry the failing step's stepId");
        }
        int failedIndex = requireExactlyOneFailedStep(steps);
        RecordedWorkflowStep failedStep = steps.get(failedIndex);
        if (!failedStep.stepId().equals(failure.stepId().get())) {
            throw new IllegalArgumentException(
                    "the overall failure's stepId must match the FAILED step's stepId");
        }
        if (!failedStep.failure().orElseThrow().equals(failure)) {
            throw new IllegalArgumentException(
                    "the overall failure must be identical to the FAILED step's own failure");
        }
        for (int i = 0; i < failedIndex; i++) {
            WorkflowStepStatus s = steps.get(i).status();
            if (s != WorkflowStepStatus.SUCCEEDED && s != WorkflowStepStatus.SKIPPED) {
                throw new IllegalArgumentException(
                        "every step before the FAILED step must be SUCCEEDED or SKIPPED");
            }
        }
        for (int i = failedIndex + 1; i < steps.size(); i++) {
            if (steps.get(i).status() != WorkflowStepStatus.NOT_RUN) {
                throw new IllegalArgumentException(
                        "every step after the FAILED step must be NOT_RUN");
            }
        }
    }

    private static void requireAllNotRun(List<RecordedWorkflowStep> steps) {
        for (RecordedWorkflowStep step : steps) {
            if (step.status() != WorkflowStepStatus.NOT_RUN) {
                throw new IllegalArgumentException(
                        "a preflight-failure recording must have every step NOT_RUN");
            }
        }
    }

    private static int requireExactlyOneFailedStep(List<RecordedWorkflowStep> steps) {
        int failedIndex = -1;
        for (int i = 0; i < steps.size(); i++) {
            if (steps.get(i).status() == WorkflowStepStatus.FAILED) {
                if (failedIndex != -1) {
                    throw new IllegalArgumentException(
                            "a FAILED recording with a step-associated failure must have exactly"
                                    + " one FAILED step");
                }
                failedIndex = i;
            }
        }
        if (failedIndex == -1) {
            throw new IllegalArgumentException(
                    "a FAILED recording whose overall failure has a stepId must have a matching"
                            + " FAILED step");
        }
        return failedIndex;
    }
}
