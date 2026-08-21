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

/**
 * Test-only fake {@link IPreparedAction} whose {@link #execute()} throws, used to produce a real
 * {@code STEP_EXCEPTION} workflow failure (backend genuinely reached and genuinely threw) rather
 * than an {@code ACTION_FACTORY_FAILED} one.
 */
final class ThrowingPreparedAction<R> implements IPreparedAction<R> {

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
        throw new RuntimeException("backend execution failed");
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
