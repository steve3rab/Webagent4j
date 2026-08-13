package io.webagent4j.locator;

import java.util.List;
import java.util.Objects;

/**
 * Ordered exact-first discovery plan.
 *
 * @param steps immutable steps executed until the first successful tier
 */
public record LocatorPlan(List<LocatorPlanStep> steps) {

    /** Defensively copies the ordered plan. */
    public LocatorPlan {
        steps = List.copyOf(Objects.requireNonNull(steps, "steps"));
        if (steps.isEmpty()) {
            throw new IllegalArgumentException("steps cannot be empty");
        }
    }
}
