package io.webagent4j.common;

import java.time.Duration;
import java.util.Objects;

/**
 * Immutable timeout categories used to avoid ambiguous global timeouts.
 *
 * @param navigation maximum navigation duration
 * @param action maximum action duration
 * @param locator maximum locator duration
 * @param networkIdle maximum wait for network idleness
 */
public record Timeouts(
        Duration navigation, Duration action, Duration locator, Duration networkIdle) {

    /** Validates all durations at construction time. */
    public Timeouts {
        requirePositive(navigation, "navigation");
        requirePositive(action, "action");
        requirePositive(locator, "locator");
        requirePositive(networkIdle, "networkIdle");
    }

    /** Returns balanced defaults for local and remote web pages. */
    public static Timeouts defaults() {
        return new Timeouts(
                Duration.ofSeconds(30),
                Duration.ofSeconds(10),
                Duration.ofSeconds(5),
                Duration.ofSeconds(30));
    }

    private static void requirePositive(Duration duration, String name) {
        Objects.requireNonNull(duration, name);
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }
}
