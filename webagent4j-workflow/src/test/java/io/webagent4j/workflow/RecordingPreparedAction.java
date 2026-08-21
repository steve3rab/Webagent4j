package io.webagent4j.workflow;

import io.webagent4j.action.ActionResult;
import io.webagent4j.action.IActionPlan;
import io.webagent4j.action.IPreparedAction;
import io.webagent4j.action.ObservationCapturePolicy;
import io.webagent4j.common.RetryPolicy;
import io.webagent4j.dom.IElement;
import io.webagent4j.verification.IVerification;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Test-only fake {@link IPreparedAction} that records the thread {@link #execute()} ran on (if a
 * counter is supplied) and delegates the actual outcome - including throwing - to a supplier.
 */
final class RecordingPreparedAction<R> implements IPreparedAction<R> {

    private final Supplier<ActionResult<R>> executeAction;
    private final AtomicLong executeThreadId;

    RecordingPreparedAction(Supplier<ActionResult<R>> executeAction, AtomicLong executeThreadId) {
        this.executeAction = executeAction;
        this.executeThreadId = executeThreadId;
    }

    @Override
    public IPreparedAction<R> precondition(Predicate<IElement> predicate) {
        return this;
    }

    @Override
    public IPreparedAction<R> require(IVerification verification) {
        return this;
    }

    @Override
    public IPreparedAction<R> expect(IVerification verification) {
        return this;
    }

    @Override
    public IPreparedAction<R> expectUrlContains(String expectedFragment) {
        return this;
    }

    @Override
    public IPreparedAction<R> timeout(Duration timeout) {
        return this;
    }

    @Override
    public IPreparedAction<R> retry(RetryPolicy retryPolicy) {
        return this;
    }

    @Override
    public IPreparedAction<R> captureObservations(ObservationCapturePolicy policy) {
        return this;
    }

    @Override
    public ActionResult<R> execute() {
        if (executeThreadId != null) {
            executeThreadId.set(Thread.currentThread().threadId());
        }
        return executeAction.get();
    }

    @Override
    public IPreparedAction<R> dryRun() {
        return this;
    }

    @Override
    public IActionPlan<R> plan() {
        throw new UnsupportedOperationException("not used by workflow action steps");
    }
}
