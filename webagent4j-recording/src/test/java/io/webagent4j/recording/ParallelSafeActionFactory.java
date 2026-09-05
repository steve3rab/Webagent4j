package io.webagent4j.recording;

import io.webagent4j.action.IPreparedAction;
import io.webagent4j.workflow.IWorkflowActionFactory;
import io.webagent4j.workflow.IWorkflowVariables;

/**
 * Test-only {@link IWorkflowActionFactory} that declares itself parallel-safe - a plain lambda
 * cannot override {@link IWorkflowActionFactory#isParallelSafe()}, so every fixture needing an
 * {@code ACTION} step inside a {@code PARALLEL} branch goes through this named class instead.
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
