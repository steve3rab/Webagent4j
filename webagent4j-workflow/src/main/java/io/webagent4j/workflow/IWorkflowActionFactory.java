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
}
