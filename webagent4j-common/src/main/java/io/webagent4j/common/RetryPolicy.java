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
        if (!Double.isFinite(backoffFactor) || backoffFactor < 1.0) {
            throw new IllegalArgumentException("backoffFactor must be finite and at least one");
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
        long delayMillis = saturatedMillis(delay);
        long maximumMillis = saturatedMillis(maximumDelay);
        if (delayMillis == 0L) {
            return Duration.ZERO;
        }
        if (delayMillis >= maximumMillis) {
            return Duration.ofMillis(maximumMillis);
        }
        double multiplier = Math.pow(backoffFactor, attempt - 2.0);
        double candidate = delayMillis * multiplier;
        if (!Double.isFinite(candidate) || candidate >= maximumMillis) {
            return Duration.ofMillis(maximumMillis);
        }
        return Duration.ofMillis(Math.min(Math.round(candidate), maximumMillis));
    }

    /** Returns whether another attempt is allowed for a result matching the supplied predicate. */
    public <T> boolean shouldRetry(int attempt, T result, Predicate<T> retryableResult) {
        Objects.requireNonNull(retryableResult, "retryableResult");
        return attempt < maxAttempts && retryableResult.test(result);
    }

    private static long saturatedMillis(Duration duration) {
        try {
            return duration.toMillis();
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
    }
}
