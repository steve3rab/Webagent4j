package io.webagent4j.recording;

import io.webagent4j.action.ActionResult;
import io.webagent4j.action.IActionPlan;
import io.webagent4j.action.IPreparedAction;
import io.webagent4j.action.ObservationCapturePolicy;
import io.webagent4j.common.RetryPolicy;
import io.webagent4j.dom.IElement;
import io.webagent4j.verification.IVerification;
import java.time.Duration;
import java.util.function.Predicate;

/** Test-only fake {@link IPreparedAction} that returns a preconfigured {@link ActionResult}. */
final class FakePreparedAction<R> implements IPreparedAction<R> {

    private final ActionResult<R> result;

    FakePreparedAction(ActionResult<R> result) {
        this.result = result;
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
        return result;
    }

    @Override
    public IPreparedAction<R> dryRun() {
        return this;
    }

    @Override
    public IActionPlan<R> plan() {
        throw new UnsupportedOperationException("not used by these tests");
    }
}
