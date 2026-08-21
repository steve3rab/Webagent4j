package io.webagent4j.recording;

import io.webagent4j.workflow.WorkflowStatus;
import io.webagent4j.workflow.WorkflowStepId;
import io.webagent4j.workflow.WorkflowStepStatus;
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
 * <p>The overall failure and the FAILED step's own failure are required to agree only on their
 * stable semantic fields - {@code type} and {@code actionFailureType} (matching {@code stepId} by
 * construction) - never on {@code safeMessage} or {@code underlyingTypeName}, which {@link
 * WorkflowReplayVerifier} itself already treats as diagnostic, not semantic.
 */
final class RecordingInvariants {

    private RecordingInvariants() {}

    static void validate(
            WorkflowStatus status,
            List<RecordedWorkflowStep> steps,
            Optional<RecordedFailure> failure) {
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
        if (failure.stepId().isEmpty()) {
            requireAllNotRun(steps);
            return;
        }
        int failedIndex = requireExactlyOneFailedStep(steps);
        RecordedWorkflowStep failedStep = steps.get(failedIndex);
        if (!failedStep.stepId().equals(failure.stepId().get())) {
            throw new IllegalArgumentException(
                    "the overall failure's stepId must match the FAILED step's stepId");
        }
        RecordedFailure stepFailure = failedStep.failure().orElseThrow();
        if (stepFailure.type() != failure.type()
                || !stepFailure.actionFailureType().equals(failure.actionFailureType())) {
            throw new IllegalArgumentException(
                    "the overall failure's type and actionFailureType must match the FAILED"
                            + " step's own failure");
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
                        "a FAILED recording whose overall failure has no stepId (a pre-execution"
                                + " failure) must have every step NOT_RUN");
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
