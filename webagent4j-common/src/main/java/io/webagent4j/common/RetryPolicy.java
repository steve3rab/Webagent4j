package io.webagent4j.common;

import java.time.Duration;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * Immutable retry configuration shared by operations that may fail transiently.
 *
 * @param maxAttempts total number of attempts, including the initial call
 * @param delay delay before the second attempt
 * @param backoffFactor multiplier applied to each subsequent delay
 * @param maximumDelay upper bound for an individual delay
 */
public record RetryPolicy(
        int maxAttempts, Duration delay, double backoffFactor, Duration maximumDelay) {

    /** Validates and defensively stores a retry policy. */
    public RetryPolicy {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be at least one");
        }
        Objects.requireNonNull(delay, "delay");
        Objects.requireNonNull(maximumDelay, "maximumDelay");
        if (delay.isNegative() || maximumDelay.isNegative()) {
            throw new IllegalArgumentException("retry delays cannot be negative");
        }
        if (backoffFactor < 1.0) {
            throw new IllegalArgumentException("backoffFactor must be at least one");
        }
    }

    /** Returns a conservative policy suitable for short browser operations. */
    public static RetryPolicy defaults() {
        return new RetryPolicy(3, Duration.ofMillis(100), 2.0, Duration.ofSeconds(2));
    }

    /** Calculates the delay before the supplied one-based attempt number. */
    public Duration delayBeforeAttempt(int attempt) {
        if (attempt < 2 || attempt > maxAttempts) {
            throw new IllegalArgumentException("attempt must be between two and maxAttempts");
        }
        double multiplier = Math.pow(backoffFactor, attempt - 2.0);
        long candidate = Math.round(delay.toMillis() * multiplier);
        return Duration.ofMillis(Math.min(candidate, maximumDelay.toMillis()));
    }

    /** Returns whether another attempt is allowed for a result matching the supplied predicate. */
    public <T> boolean shouldRetry(int attempt, T result, Predicate<T> retryableResult) {
        Objects.requireNonNull(retryableResult, "retryableResult");
        return attempt < maxAttempts && retryableResult.test(result);
    }
}
