package io.webagent4j.verification;

import java.time.Duration;
import java.util.Objects;

/** Immutable result of one deterministic verification. */
public record VerificationResult(
        boolean success,
        VerificationType type,
        String description,
        String expected,
        String actual,
        Duration duration,
        boolean timedOut) {

    /** Validates diagnostic values. */
    public VerificationResult {
        Objects.requireNonNull(type, "type");
        description = Objects.requireNonNull(description, "description");
        expected = Objects.requireNonNull(expected, "expected");
        actual = Objects.requireNonNull(actual, "actual");
        Objects.requireNonNull(duration, "duration");
        if (duration.isNegative()) {
            throw new IllegalArgumentException("duration cannot be negative");
        }
    }

    /** Compatibility constructor for simple immediate verifications. */
    public VerificationResult(boolean success, String description, String actual) {
        this(
                success,
                VerificationType.CUSTOM,
                description,
                description,
                actual,
                Duration.ZERO,
                false);
    }

    /** Returns a copy carrying polling duration and timeout information. */
    public VerificationResult withTiming(Duration elapsed, boolean timeout) {
        return new VerificationResult(
                success, type, description, expected, actual, elapsed, timeout);
    }
}
