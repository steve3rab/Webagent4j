package io.webagent4j.workflow;

import io.webagent4j.action.ActionResult;
import io.webagent4j.action.IActionPlan;
import io.webagent4j.action.IPreparedAction;
import io.webagent4j.action.ObservationCapturePolicy;
import io.webagent4j.common.RetryPolicy;
import io.webagent4j.dom.IElement;
import io.webagent4j.verification.IVerification;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

/**
 * Test-only fake {@link IPreparedAction} that records exactly how many times {@link #execute()} was
 * called and returns a preconfigured {@link ActionResult}.
 */
final class FakePreparedAction<R> implements IPreparedAction<R> {

    private final ActionResult<R> result;
    private final AtomicInteger executeCount;

    FakePreparedAction(ActionResult<R> result, AtomicInteger executeCount) {
        this.result = result;
        this.executeCount = executeCount;
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
        executeCount.incrementAndGet();
        return result;
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
