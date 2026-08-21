package io.webagent4j.workflow;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

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
