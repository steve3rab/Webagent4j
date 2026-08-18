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
 * saturated rather than allowed to overflow: an implausibly large timeout clamps to the largest
 * representable deadline instead of wrapping around to a point in the past.
 */
public final class WaitBudget {

    private final long startNanos;
    private final long deadlineNanos;
    private final IMonotonicClock clock;

    private WaitBudget(long startNanos, long deadlineNanos, IMonotonicClock clock) {
        this.startNanos = startNanos;
        this.deadlineNanos = deadlineNanos;
        this.clock = clock;
    }

    /** Starts a new budget of {@code timeout}, measured from now against {@code clock}. */
    public static WaitBudget start(Duration timeout, IMonotonicClock clock) {
        Objects.requireNonNull(timeout, "timeout");
        Objects.requireNonNull(clock, "clock");
        if (timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must not be negative");
        }
        long start = clock.nanoTime();
        return new WaitBudget(start, saturatedAdd(start, timeout.toNanos()), clock);
    }

    /** Returns the time elapsed since this budget started. Never negative. */
    public Duration elapsed() {
        return Duration.ofNanos(Math.max(0L, clock.nanoTime() - startNanos));
    }

    /** Returns the time left before the deadline. Never negative; zero once expired. */
    public Duration remaining() {
        return Duration.ofNanos(Math.max(0L, deadlineNanos - clock.nanoTime()));
    }

    /** Returns whether the deadline has passed. */
    public boolean expired() {
        return clock.nanoTime() >= deadlineNanos;
    }

    private static long saturatedAdd(long left, long right) {
        long sum = left + right;
        // Overflow occurred iff both operands share a sign that the result does not.
        if (((left ^ sum) & (right ^ sum)) < 0) {
            return Long.MAX_VALUE;
        }
        return sum;
    }
}
