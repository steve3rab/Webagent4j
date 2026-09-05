package io.webagent4j.workflow;

import io.webagent4j.action.IPreparedAction;

/**
 * Test-only {@link IWorkflowActionFactory} that declares itself parallel-safe - a plain lambda
 * cannot override {@link IWorkflowActionFactory#isParallelSafe()}, so every fixture that needs an
 * {@code ACTION} step usable inside a {@link WorkflowStepType#PARALLEL} branch goes through this
 * named class instead.
 */
final class ParallelSafeActionFactory<R> implements IWorkflowActionFactory<R> {

    @FunctionalInterface
    interface Delegate<R> {
        IPreparedAction<R> prepare(IWorkflowVariables variables);
    }

    private final Delegate<R> delegate;

    ParallelSafeActionFactory(Delegate<R> delegate) {
        this.delegate = delegate;
    }

    @Override
    public IPreparedAction<R> prepare(IWorkflowVariables variables) {
        return delegate.prepare(variables);
    }

    @Override
    public boolean isParallelSafe() {
        return true;
    }
}
