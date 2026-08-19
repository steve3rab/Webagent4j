package io.webagent4j.wait;

import java.time.Duration;
import java.util.Objects;

/**
 * The one deterministic polling coordinator shared by every domain that needs to wait for a
 * condition: locator resolution, verification, and action stabilization/postconditions.
 *
 * <p>The engine owns exactly four responsibilities: when to poll, when to stop, how much time
 * remains, and when a satisfied result has been stable long enough. It owns nothing about what is
 * being waited for - that is entirely the {@link IWaitProbe}'s responsibility - and it never
 * performs a side effect itself; see {@link IWaitProbe} for why a probe must be read-only.
 *
 * <p>Every call always evaluates the probe at least once, immediately, before any sleep: a
 * condition that already holds returns without ever parking the thread. Between polls, the engine
 * sleeps for at most {@code min(policy.pollingInterval(), budget.remaining())} - it never
 * oversleeps past the deadline, and it never issues a second sleep after the deadline has passed.
 *
 * <p>This class does not itself poll a browser, a network resource, or any other backend; it has no
 * knowledge of any of those concepts. Composing one {@link WaitEngine} instance per domain adapter
 * (locator, verification, action) rather than nesting one adapter's wait inside another's probe
 * keeps exactly one coordinator active for any given logical wait - see the class-level warning on
 * {@link IWaitProbe} about side effects for the related, and equally important, rule against
 * nesting one wait inside another's probe.
 */
public final class WaitEngine {

    private final IMonotonicClock clock;
    private final IWaitSleeper sleeper;

    /** Creates an engine using the production monotonic clock and thread-parking sleeper. */
    public WaitEngine() {
        this(IMonotonicClock.systemClock(), IWaitSleeper.parking());
    }

    /**
     * Creates an engine with an injectable clock and sleeper, for deterministic fake-time tests.
     */
    public WaitEngine(IMonotonicClock clock, IWaitSleeper sleeper) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.sleeper = Objects.requireNonNull(sleeper, "sleeper");
    }

    /**
     * Returns the clock this engine was built with, so callers can start a shared {@link
     * WaitBudget}.
     */
    public IMonotonicClock clock() {
        return clock;
    }

    /**
     * Starts a fresh {@link WaitBudget} of {@code timeout} against this engine's clock, then
     * awaits.
     */
    public <T> WaitResult<T> await(Duration timeout, WaitPolicy policy, IWaitProbe<T> probe) {
        return await(WaitBudget.start(timeout, clock), policy, probe);
    }

    /**
     * Polls {@code probe} under {@code policy} until it is satisfied (and, if requested, stable) or
     * {@code budget} expires.
     *
     * @throws WaitInterruptedException if the current thread is interrupted before a poll or while
     *     sleeping between polls; the interrupt status is preserved
     * @throws IllegalStateException if the policy requests a stability window but a satisfied
     *     sample carries no stability key
     */
    public <T> WaitResult<T> await(WaitBudget budget, WaitPolicy policy, IWaitProbe<T> probe) {
        Objects.requireNonNull(budget, "budget");
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(probe, "probe");

        int attempts = 0;
        Object stableKey = null;
        long stableSinceNanos = 0L;
        WaitSample<T> lastSample = WaitSample.pending();

        while (true) {
            requireNotInterrupted();
            attempts++;
            lastSample = probe.evaluate();

            if (lastSample.status() == WaitSample.Status.SATISFIED) {
                if (policy.stableFor().isEmpty()) {
                    return success(lastSample, attempts, budget, null);
                }
                Object key =
                        lastSample
                                .stabilityKey()
                                .orElseThrow(
                                        () ->
                                                new IllegalStateException(
                                                        "WaitPolicy requires a stability window but"
                                                                + " the probe returned a satisfied"
                                                                + " sample with no stability key"));
                long now = clock.nanoTime();
                if (stableKey == null || !stableKey.equals(key)) {
                    stableKey = key;
                    stableSinceNanos = now;
                }
                Duration stableDuration = Duration.ofNanos(Math.max(0L, now - stableSinceNanos));
                if (stableDuration.compareTo(policy.stableFor().orElseThrow()) >= 0) {
                    return success(lastSample, attempts, budget, stableDuration);
                }
            } else {
                stableKey = null;
                stableSinceNanos = 0L;
            }

            if (budget.expired()) {
                return timedOut(lastSample, attempts, budget);
            }
            Duration sleepFor = min(policy.pollingInterval(), budget.remaining());
            sleeper.sleep(sleepFor);
        }
    }

    private static void requireNotInterrupted() {
        if (Thread.currentThread().isInterrupted()) {
            throw new WaitInterruptedException("Wait was interrupted");
        }
    }

    private static <T> WaitResult<T> success(
            WaitSample<T> sample, int attempts, WaitBudget budget, Duration achievedStability) {
        return new WaitResult<>(
                WaitStatus.SUCCESS,
                attempts,
                budget.elapsed(),
                sample.value(),
                java.util.Optional.ofNullable(achievedStability));
    }

    private static <T> WaitResult<T> timedOut(
            WaitSample<T> sample, int attempts, WaitBudget budget) {
        return new WaitResult<>(
                WaitStatus.TIMED_OUT,
                attempts,
                budget.elapsed(),
                sample.value(),
                java.util.Optional.empty());
    }

    private static Duration min(Duration left, Duration right) {
        return left.compareTo(right) <= 0 ? left : right;
    }
}
