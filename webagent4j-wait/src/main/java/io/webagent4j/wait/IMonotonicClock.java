package io.webagent4j.wait;

/**
 * Source of monotonic time for deadline arithmetic.
 *
 * <p>A timeout is never measured against wall-clock time ({@code Instant.now()}, {@code
 * System.currentTimeMillis()}), which can jump backwards or forwards (NTP correction, daylight
 * saving, a system clock change). {@link #nanoTime()} is the only authority a {@link WaitBudget}
 * trusts, exactly mirroring the guarantee {@link System#nanoTime()} itself makes: the returned
 * value is only meaningful relative to another value returned by the same clock, never as an
 * absolute point in time.
 */
@FunctionalInterface
public interface IMonotonicClock {

    /** Returns the current value of the clock, in nanoseconds, from an arbitrary fixed origin. */
    long nanoTime();

    /** Returns the production clock, backed by {@link System#nanoTime()}. */
    static IMonotonicClock systemClock() {
        return System::nanoTime;
    }
}
