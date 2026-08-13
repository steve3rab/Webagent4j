package io.webagent4j.action;

import java.time.Duration;
import java.util.Objects;

/** Immutable stabilization outcome. */
public record StabilizationResult(boolean stable, Duration duration, String description) {

    /** Validates stabilization diagnostics. */
    public StabilizationResult {
        Objects.requireNonNull(duration, "duration");
        description = Objects.requireNonNull(description, "description");
    }

    /** Returns the zero-cost default relying on backend actionability and verification polling. */
    public static StabilizationResult none() {
        return new StabilizationResult(true, Duration.ZERO, "verification-driven stabilization");
    }
}
