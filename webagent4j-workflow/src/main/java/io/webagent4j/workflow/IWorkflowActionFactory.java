package io.webagent4j.workflow;

import io.webagent4j.action.IPreparedAction;

/**
 * Builds a fresh {@link IPreparedAction} for one action-backed workflow step.
 *
 * <p>This is a <em>preparation</em> factory: {@link #prepare} must configure the action but never
 * perform it. {@link WorkflowEngine} calls {@link #prepare} at most once per workflow execution,
 * only when the step actually runs (never when its condition is false, never during {@link
 * Workflow.Builder#build()} or any other structural validation), and immediately calls {@link
 * IPreparedAction#execute()} on the result exactly once.
 *
 * <p>A caller typically closes over an already-open {@code IPage} or similar caller-owned resource;
 * {@code WorkflowEngine} never creates, owns, or closes that resource.
 *
 * @param <R> the result type produced by the prepared action
 */
@FunctionalInterface
public interface IWorkflowActionFactory<R> {

    /** Builds a fresh, not-yet-executed prepared action using the current execution variables. */
    IPreparedAction<R> prepare(IWorkflowVariables variables);

    /**
     * Returns whether this factory's prepared actions are safe to run concurrently with sibling
     * branches inside a {@link WorkflowStepType#PARALLEL} step - added in 1.3.0. Defaults to {@code
     * false}: an {@link IWorkflowActionFactory} is never treated as parallel-safe unless it
     * explicitly overrides this method to declare so. {@link Workflow.Builder#build()} rejects a
     * {@code PARALLEL} branch containing an {@code ACTION} step whose factory returns {@code false}
     * here (or whose call throws) with {@link WorkflowValidationCode#PARALLEL_BRANCH_UNSAFE_STEP} -
     * fail-closed, since {@link WorkflowEngine} has no way to inspect what an arbitrary {@link
     * #prepare} and the {@link IPreparedAction} it returns will actually do.
     *
     * <p>Overriding this to return {@code true} is a caller's explicit, auditable assertion that
     * this factory's prepared actions never mutate page state, never navigate, and never perform
     * any action outside a read-only, side-effect-free observation - see {@code
     * docs/workflow.md#parallel}. This method must itself be side-effect-free and must not depend
     * on {@code prepare}'s arguments: {@link Workflow.Builder#build()} calls it once, at
     * definition-validation time, on an arbitrary instance of the factory, before any execution
     * variables exist. A plain lambda (the common shape for {@link #prepare} alone) cannot usefully
     * override a default method with a different implementation per instance, so declaring parallel
     * safety in practice requires a named class or a method reference to one.
     */
    default boolean isParallelSafe() {
        return false;
    }
}
