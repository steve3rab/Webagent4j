package io.webagent4j.wait;

import java.time.Duration;
import java.util.Objects;

/**
 * A monotonic deadline, optionally shared across several sequential sub-operations.
 *
 * <p>One {@link WaitBudget} represents one logical operation's total time allowance. Passing the
 * same instance to several sub-operations - three postconditions, three nested locator scopes -
 * makes them consume time from the same shrinking allowance instead of each silently receiving a
 * full, independent timeout: if the first sub-operation consumes 1.5 of a 5 second budget, the
 * second sees {@link #remaining()} return 3.5 seconds, not a fresh 5.
 *
 * <p>All arithmetic is based on {@link IMonotonicClock#nanoTime()}, never wall-clock time, and is
 * rollover-safe and saturated rather than allowed to overflow: an implausibly large timeout clamps
 * to the largest representable allowance instead of wrapping around.
 */
public final class WaitBudget {

    private final long startNanos;
    private final long timeoutNanos;
    private final IMonotonicClock clock;

    private WaitBudget(long startNanos, long timeoutNanos, IMonotonicClock clock) {
        this.startNanos = startNanos;
        this.timeoutNanos = timeoutNanos;
        this.clock = clock;
    }

    /** Starts a new budget of {@code timeout}, measured from now against {@code clock}. */
    public static WaitBudget start(Duration timeout, IMonotonicClock clock) {
        Objects.requireNonNull(timeout, "timeout");
        Objects.requireNonNull(clock, "clock");
        if (timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must not be negative");
        }
        return new WaitBudget(clock.nanoTime(), saturatedNanos(timeout), clock);
    }

    /**
     * Converts {@code duration} to nanoseconds, saturating to {@link Long#MAX_VALUE} instead of
     * letting {@link Duration#toNanos()} throw {@link ArithmeticException} for an implausibly large
     * duration such as {@code Duration.ofSeconds(Long.MAX_VALUE)}. {@code duration} is already
     * known non-negative by the caller.
     */
    private static long saturatedNanos(Duration duration) {
        try {
            return duration.toNanos();
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
    }

    /** Returns the time elapsed since this budget started. Never negative. */
    public Duration elapsed() {
        return Duration.ofNanos(elapsedNanos());
    }

    /** Returns the time left before the deadline. Never negative; zero once expired. */
    public Duration remaining() {
        return Duration.ofNanos(Math.max(0L, timeoutNanos - elapsedNanos()));
    }

    /** Returns whether the deadline has passed. */
    public boolean expired() {
        return elapsedNanos() >= timeoutNanos;
    }

    private long elapsedNanos() {
        return Math.max(0L, clock.nanoTime() - startNanos);
    }
}
