package io.webagent4j.verification;

import io.webagent4j.wait.WaitEngine;
import io.webagent4j.wait.WaitInterruptedException;
import io.webagent4j.wait.WaitPolicy;
import io.webagent4j.wait.WaitResult;
import io.webagent4j.wait.WaitSample;
import java.time.Duration;
import java.util.Objects;

/**
 * Central polling loop used by action preconditions and postconditions.
 *
 * <p>An adapter over the shared {@code webagent4j-wait} {@link WaitEngine}: this class owns no
 * timing, sleeping, or deadline logic of its own. {@link
 * IVerification#verify(IVerificationContext)} is the probe; the engine decides when to poll and
 * when to stop, against a monotonic clock rather than wall-clock time.
 */
public final class VerificationPoller {

    private final WaitEngine engine;

    /** Creates a poller using the production monotonic clock and thread-parking sleeper. */
    public VerificationPoller() {
        this(new WaitEngine());
    }

    /** Creates a poller backed by an explicit engine, for deterministic fake-time tests. */
    public VerificationPoller(WaitEngine engine) {
        this.engine = Objects.requireNonNull(engine, "engine");
    }

    /** Polls one side-effect-free condition until success or the positive timeout expires. */
    public VerificationResult await(
            IVerification verification,
            IVerificationContext context,
            Duration timeout,
            Duration interval) {
        Objects.requireNonNull(verification, "verification");
        Objects.requireNonNull(context, "context");
        requirePositive(timeout, "timeout");
        requirePositive(interval, "interval");

        VerificationResult[] latest = new VerificationResult[1];
        WaitResult<VerificationResult> waitResult;
        try {
            waitResult =
                    engine.await(
                            timeout,
                            WaitPolicy.pollingEvery(interval),
                            () -> probe(verification, context, latest));
        } catch (WaitInterruptedException interrupted) {
            throw new VerificationInterruptedException(
                    "Verification polling was interrupted", interrupted);
        }
        return latest[0].withTiming(waitResult.elapsed(), !waitResult.success());
    }

    private static WaitSample<VerificationResult> probe(
            IVerification verification, IVerificationContext context, VerificationResult[] latest) {
        VerificationResult result = verification.verify(context);
        latest[0] = result;
        return result.success() ? WaitSample.satisfied(result) : WaitSample.pending();
    }

    private static void requirePositive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }
}
