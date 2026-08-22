package io.webagent4j.workflow;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Immutable, structured outcome of one {@link WorkflowEngine#execute(Workflow, WorkflowInputs)}
 * call.
 *
 * <p><b>Secret safety:</b> {@link #toString()} always masks every output declared {@link
 * WorkflowVariable#secret()}. {@link #output(WorkflowVariable)} is explicit typed retrieval and
 * intentionally returns the real value even for a secret output - the same explicit-read-vs-
 * incidental-rendering boundary described on {@link IWorkflowVariables}.
 *
 * @param workflowId the executed workflow's identifier
 * @param status the overall terminal outcome
 * @param steps every step's result, in workflow definition order
 * @param failure the overall failure, present exactly when {@code status} is {@link
 *     WorkflowStatus#FAILED}
 */
public record WorkflowResult(
        WorkflowId workflowId,
        WorkflowStatus status,
        List<WorkflowStepResult> steps,
        WorkflowOutputs outputs,
        Optional<WorkflowFailure> failure) {

    private static final Set<WorkflowFailureType> PREFLIGHT_FAILURE_TYPES =
            EnumSet.of(
                    WorkflowFailureType.MISSING_REQUIRED_INPUT,
                    WorkflowFailureType.INPUT_TYPE_MISMATCH,
                    WorkflowFailureType.UNDECLARED_INPUT);

    /** Validates and defensively copies result data. */
    public WorkflowResult {
        Objects.requireNonNull(workflowId, "workflowId");
        Objects.requireNonNull(status, "status");
        steps = List.copyOf(Objects.requireNonNull(steps, "steps"));
        Objects.requireNonNull(outputs, "outputs");
        failure = Objects.requireNonNull(failure, "failure");
        if (status == WorkflowStatus.FAILED && failure.isEmpty()) {
            throw new IllegalArgumentException("a FAILED result must carry a failure");
        }
        if (status != WorkflowStatus.FAILED && failure.isPresent()) {
            throw new IllegalArgumentException("only a FAILED result may carry a failure");
        }
        requireUniqueStepIds(steps);
        if (status == WorkflowStatus.COMPLETED) {
            requireOnlySucceededOrSkipped(steps);
        } else {
            requireValidFailedTrace(steps, failure.orElseThrow());
        }
    }

    private static void requireUniqueStepIds(List<WorkflowStepResult> steps) {
        Set<WorkflowStepId> seen = new HashSet<>();
        for (WorkflowStepResult step : steps) {
            if (!seen.add(step.stepId())) {
                throw new IllegalArgumentException(
                        "a workflow result cannot contain duplicate step IDs");
            }
        }
    }

    private static void requireOnlySucceededOrSkipped(List<WorkflowStepResult> steps) {
        for (WorkflowStepResult step : steps) {
            if (step.status() != WorkflowStepStatus.SUCCEEDED
                    && step.status() != WorkflowStepStatus.SKIPPED) {
                throw new IllegalArgumentException(
                        "a COMPLETED workflow result's steps must all be SUCCEEDED or SKIPPED");
            }
        }
    }

    private static void requireValidFailedTrace(
            List<WorkflowStepResult> steps, WorkflowFailure failure) {
        if (PREFLIGHT_FAILURE_TYPES.contains(failure.type())) {
            for (WorkflowStepResult step : steps) {
                if (step.status() != WorkflowStepStatus.NOT_RUN) {
                    throw new IllegalArgumentException(
                            "a preflight-failure result must have every step NOT_RUN");
                }
            }
            return;
        }
        int failedIndex = requireExactlyOneFailedStep(steps);
        WorkflowStepResult failedStep = steps.get(failedIndex);
        if (!failedStep.stepId().equals(failure.stepId().orElseThrow())) {
            throw new IllegalArgumentException(
                    "the overall failure's stepId must match the FAILED step's stepId");
        }
        if (!failedStep.failure().orElseThrow().equals(failure)) {
            throw new IllegalArgumentException(
                    "the overall failure must equal the FAILED step's own failure");
        }
        for (int index = 0; index < failedIndex; index++) {
            WorkflowStepStatus stepStatus = steps.get(index).status();
            if (stepStatus != WorkflowStepStatus.SUCCEEDED
                    && stepStatus != WorkflowStepStatus.SKIPPED) {
                throw new IllegalArgumentException(
                        "every step before the FAILED step must be SUCCEEDED or SKIPPED");
            }
        }
        for (int index = failedIndex + 1; index < steps.size(); index++) {
            if (steps.get(index).status() != WorkflowStepStatus.NOT_RUN) {
                throw new IllegalArgumentException(
                        "every step after the FAILED step must be NOT_RUN");
            }
        }
    }

    private static int requireExactlyOneFailedStep(List<WorkflowStepResult> steps) {
        int failedIndex = -1;
        for (int index = 0; index < steps.size(); index++) {
            if (steps.get(index).status() == WorkflowStepStatus.FAILED) {
                if (failedIndex != -1) {
                    throw new IllegalArgumentException(
                            "a runtime-failure result must have exactly one FAILED step");
                }
                failedIndex = index;
            }
        }
        if (failedIndex == -1) {
            throw new IllegalArgumentException(
                    "a runtime-failure result must have a matching FAILED step");
        }
        return failedIndex;
    }

    /** Returns whether {@link #status()} is {@link WorkflowStatus#COMPLETED}. */
    public boolean completed() {
        return status == WorkflowStatus.COMPLETED;
    }

    /**
     * Returns the value {@code variable} was published as, if any step produced it - the real value
     * even for a secret variable (see the class-level note on secret safety).
     */
    public <T> Optional<T> output(WorkflowVariable<T> variable) {
        return outputs.find(variable);
    }

    /** Throws {@link WorkflowFailedException} if this result is not {@link #completed()}. */
    public WorkflowResult throwIfFailed() {
        if (!completed()) {
            throw new WorkflowFailedException(this);
        }
        return this;
    }

    /** Renders workflow ID, status, every step's safe summary, and masked outputs. */
    @Override
    public String toString() {
        StringBuilder text =
                new StringBuilder("WorkflowResult[workflowId=")
                        .append(workflowId)
                        .append(", status=")
                        .append(status);
        text.append(", steps=[");
        for (int i = 0; i < steps.size(); i++) {
            if (i > 0) {
                text.append(", ");
            }
            WorkflowStepResult step = steps.get(i);
            text.append(step.stepId().value()).append('=').append(step.status());
        }
        text.append(']');
        text.append(", outputs=").append(outputs);
        failure.ifPresent(f -> text.append(", failure=").append(f));
        return text.append(']').toString();
    }
}
