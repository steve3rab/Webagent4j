package io.webagent4j.locator;

import java.util.Objects;

/**
 * One ordered discovery step in a deterministic locator plan.
 *
 * @param query focused backend query
 * @param description human-readable reason for the step
 */
public record LocatorPlanStep(LocatorBackendQuery query, String description) {

    /** Validates plan step data. */
    public LocatorPlanStep {
        Objects.requireNonNull(query, "query");
        description = Objects.requireNonNull(description, "description");
    }
}
