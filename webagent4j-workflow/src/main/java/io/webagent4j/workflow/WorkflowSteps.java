package io.webagent4j.workflow;

import io.webagent4j.workflow.internal.ActionWorkflowStep;
import io.webagent4j.workflow.internal.AssignWorkflowStep;
import java.util.Objects;

/**
 * Factory for the two built-in {@link IWorkflowStep} kinds Phase 0.8 supports.
 *
 * <p>There is no generic {@code Runnable}/{@code Consumer<Map<String,Object>>} step here - every
 * step is either backed by the real action pipeline ({@link #action}) or a single deterministic
 * literal assignment ({@link #assign}), preserving type safety, structural validation, and secret
 * provenance (see {@code docs/workflow.md#steps}).
 */
public final class WorkflowSteps {

    private WorkflowSteps() {}

    /** An action step with no declared output variable. */
    public static <R> IWorkflowStep action(String stepId, IWorkflowActionFactory<R> factory) {
        Objects.requireNonNull(factory, "factory");
        return new ActionWorkflowStep<>(new WorkflowStepId(stepId), factory);
    }

    /**
     * An action step that publishes the underlying {@code ActionResult}'s value to {@code output}
     * on success.
     */
    public static <R> IWorkflowStep action(
            String stepId, IWorkflowActionFactory<R> factory, WorkflowVariable<R> output) {
        Objects.requireNonNull(factory, "factory");
        Objects.requireNonNull(output, "output");
        return new ActionWorkflowStep<>(new WorkflowStepId(stepId), factory, output);
    }

    /**
     * A step that deterministically assigns literal {@code value} to non-secret {@code variable}.
     *
     * <p>Secret literals are intentionally not supported: a secret value assigned here would live
     * permanently inside the immutable, reusable {@link Workflow} definition rather than being
     * supplied fresh per execution through {@link WorkflowInputs} - prefer a secret {@link
     * WorkflowInputs} input, or a secret action output, instead.
     *
     * @throws IllegalArgumentException if {@code variable} is {@link WorkflowVariable#secret()}, or
     *     {@code value} is null or not assignable to {@code variable}'s declared type
     */
    public static <T> IWorkflowStep assign(String stepId, WorkflowVariable<T> variable, T value) {
        Objects.requireNonNull(variable, "variable");
        if (variable.secret()) {
            throw new IllegalArgumentException(
                    "assign does not support secret variable '"
                            + variable.name()
                            + "' - secret literals would live permanently inside the workflow"
                            + " definition; supply it as a WorkflowInputs input instead");
        }
        variable.requireValid(value);
        return new AssignWorkflowStep<>(new WorkflowStepId(stepId), variable, value);
    }
}
