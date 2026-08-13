package io.webagent4j.robustness;

import io.webagent4j.locator.api.ElementRole;
import java.util.Objects;
import java.util.Set;

record RobustnessScenario(
        String id,
        String description,
        String fixture,
        DifficultyLevel difficulty,
        ScenarioExpectation expectation,
        Set<RobustnessTag> tags,
        ElementRole role,
        MatchMode match,
        String query,
        String expectedTarget,
        ElementRole scopeRole,
        String scopeName,
        boolean visibleOnly,
        boolean enabledOnly,
        boolean clickableOnly,
        boolean waitUntilVisible,
        long timeoutMillis) {

    RobustnessScenario {
        id = requireText(id, "id");
        description = requireText(description, "description");
        fixture = requireText(fixture, "fixture");
        Objects.requireNonNull(difficulty, "difficulty");
        Objects.requireNonNull(expectation, "expectation");
        tags = Set.copyOf(Objects.requireNonNull(tags, "tags"));
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(match, "match");
        query = requireText(query, "query");
        expectedTarget = expectedTarget == null ? "" : expectedTarget.strip();
        scopeName = scopeName == null ? "" : scopeName.strip();
        if ((scopeRole == null) != scopeName.isBlank()) {
            throw new IllegalArgumentException("scopeRole and scopeName must be supplied together");
        }
        if (timeoutMillis < 0) {
            throw new IllegalArgumentException("timeoutMillis cannot be negative");
        }
    }

    boolean scoped() {
        return scopeRole != null;
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " cannot be blank");
        }
        return value.strip();
    }
}
