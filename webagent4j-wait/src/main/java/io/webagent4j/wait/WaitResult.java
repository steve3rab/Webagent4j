package io.webagent4j.wait;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * Outcome of one {@link WaitEngine#await(WaitBudget, WaitPolicy, IWaitProbe)} call.
 *
 * @param status {@link WaitStatus#SUCCESS} or {@link WaitStatus#TIMED_OUT}
 * @param attempts number of times the probe was evaluated, always at least one
 * @param elapsed time spent waiting, measured against the budget's clock
 * @param value the last sample's value; present on success, and on a timeout only if the very last
 *     probe happened to report a satisfied (but insufficiently stable) sample
 * @param achievedStability how long the final value remained continuously satisfied; only ever
 *     present on {@link WaitStatus#SUCCESS} when the policy requested a stability window
 */
public record WaitResult<T>(
        WaitStatus status,
        int attempts,
        Duration elapsed,
        Optional<T> value,
        Optional<Duration> achievedStability) {

    /** Validates internal consistency. */
    public WaitResult {
        Objects.requireNonNull(status, "status");
        if (attempts < 1) {
            throw new IllegalArgumentException("attempts must be at least one");
        }
        Objects.requireNonNull(elapsed, "elapsed");
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(achievedStability, "achievedStability");
    }

    /** Returns whether this result is {@link WaitStatus#SUCCESS}. */
    public boolean success() {
        return status == WaitStatus.SUCCESS;
    }
}
