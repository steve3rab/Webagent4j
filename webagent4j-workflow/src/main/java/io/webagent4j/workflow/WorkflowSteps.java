package io.webagent4j.workflow;

import java.util.List;
import java.util.Objects;

/**
 * Factory for the built-in {@link IWorkflowStep} kinds this module supports.
 *
 * <p>There is no generic {@code Runnable}/{@code Consumer<Map<String,Object>>} step here - every
 * step is either backed by the real action pipeline ({@link #action}), a single deterministic
 * literal assignment ({@link #assign}), or a deterministic if/else branch ({@link #ifElse}, {@link
 * #ifThen}) over more steps of these same kinds - preserving type safety, structural validation,
 * and secret provenance (see {@code docs/workflow.md#steps}).
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

    /**
     * A deterministic if/else step: {@link WorkflowEngine} evaluates {@code condition} exactly once
     * when this step is reached and then executes exactly one of {@code thenSteps} (if it evaluated
     * to {@code true}) or {@code elseSteps} (if {@code false}) - never both, never neither, and
     * never re-evaluates {@code condition} while running the selected branch. The branch that is
     * not selected produces zero step executions and zero backend side effects: it is never run for
     * validation, dry-run, or as a fallback (see {@code docs/workflow.md#branching}).
     *
     * <p>If {@code condition}'s evaluation itself fails (throws, or its {@code describe()} is
     * malformed), this step fails closed with {@link
     * WorkflowFailureType#CONDITION_EVALUATION_FAILED}: neither branch runs - a failed evaluation
     * is never treated as a {@code false} decision.
     *
     * <p>{@code thenSteps} and {@code elseSteps} may themselves contain {@code ifElse}/{@code
     * ifThen} steps: nested branching works the same way at every depth. Every step ID across the
     * whole workflow - including inside every branch, at every nesting depth - must still be
     * unique; {@link Workflow.Builder#build()} rejects a collision anywhere in the tree, not only
     * among top-level steps.
     *
     * <p>Unlike {@link #action} and {@link #assign}, the returned step does not support {@link
     * IWorkflowStep#when} - see {@link ConditionalWorkflowStep}'s Javadoc for why.
     *
     * @throws IllegalArgumentException if {@code thenSteps} or {@code elseSteps} is empty
     */
    public static IWorkflowStep ifElse(
            String stepId,
            IWorkflowCondition condition,
            List<IWorkflowStep> thenSteps,
            List<IWorkflowStep> elseSteps) {
        Objects.requireNonNull(condition, "condition");
        requireNonEmptyBranch(thenSteps, "thenSteps");
        requireNonEmptyBranch(elseSteps, "elseSteps");
        return new ConditionalWorkflowStep(
                new WorkflowStepId(stepId), condition, thenSteps, elseSteps);
    }

    /**
     * Same as {@link #ifElse} without an else branch: a {@code false} decision is a no-op success
     * for this step - {@code thenSteps} never runs, and this step still counts as {@link
     * WorkflowStepStatus#SUCCEEDED}, not {@link WorkflowStepStatus#SKIPPED} (which is reserved for
     * a step's own optional {@link IWorkflowStep#when} guard evaluating false, a different
     * mechanism this step does not support - see {@link #ifElse}).
     *
     * @throws IllegalArgumentException if {@code thenSteps} is empty
     */
    public static IWorkflowStep ifThen(
            String stepId, IWorkflowCondition condition, List<IWorkflowStep> thenSteps) {
        Objects.requireNonNull(condition, "condition");
        requireNonEmptyBranch(thenSteps, "thenSteps");
        return new ConditionalWorkflowStep(new WorkflowStepId(stepId), condition, thenSteps, null);
    }

    private static void requireNonEmptyBranch(List<IWorkflowStep> steps, String paramName) {
        Objects.requireNonNull(steps, paramName);
        if (steps.isEmpty()) {
            throw new IllegalArgumentException(paramName + " must contain at least one step");
        }
    }
}
