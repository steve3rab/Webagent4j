package io.webagent4j.action;

import io.webagent4j.common.RetryPolicy;
import java.time.Duration;
import java.util.Objects;

/** Immutable centralized action timing, retry, and observation defaults. */
public record ActionOptions(
        Duration timeout,
        Duration verificationInterval,
        RetryPolicy resolutionRetry,
        ObservationCapturePolicy observationCapture) {

    /** Validates positive timeouts and immutable policy values. */
    public ActionOptions {
        requirePositive(timeout, "timeout");
        requirePositive(verificationInterval, "verificationInterval");
        Objects.requireNonNull(resolutionRetry, "resolutionRetry");
        Objects.requireNonNull(observationCapture, "observationCapture");
    }

    /** Returns conservative defaults suitable for ordinary page actions. */
    public static ActionOptions defaults() {
        return new ActionOptions(
                Duration.ofSeconds(5),
                Duration.ofMillis(50),
                RetryPolicy.defaults(),
                ObservationCapturePolicy.WHEN_REQUIRED);
    }

    private static void requirePositive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }
}
