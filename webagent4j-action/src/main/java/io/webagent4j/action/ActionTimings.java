package io.webagent4j.action;

import java.time.Duration;
import java.util.Objects;

/** Immutable stage durations suitable for metrics and diagnostics. */
public record ActionTimings(
        Duration total,
        Duration resolution,
        Duration preconditions,
        Duration execution,
        Duration stabilization,
        Duration verification) {

    /** Validates all non-negative durations. */
    public ActionTimings {
        requireDuration(total, "total");
        requireDuration(resolution, "resolution");
        requireDuration(preconditions, "preconditions");
        requireDuration(execution, "execution");
        requireDuration(stabilization, "stabilization");
        requireDuration(verification, "verification");
    }

    /** Creates timings when only total time is available. */
    public static ActionTimings empty(Duration total) {
        return new ActionTimings(
                total, Duration.ZERO, Duration.ZERO, Duration.ZERO, Duration.ZERO, Duration.ZERO);
    }

    private static void requireDuration(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isNegative()) {
            throw new IllegalArgumentException(name + " cannot be negative");
        }
    }
}
