package io.webagent4j.observation;

import java.util.Objects;

/** Immutable identifier unique to one captured observation without global mutable state. */
public record ObservationId(String value) {

    /** Validates the identifier value. */
    public ObservationId {
        value = requireText(value, "value");
    }

    private static String requireText(String value, String name) {
        String result = Objects.requireNonNull(value, name).trim();
        if (result.isEmpty()) {
            throw new IllegalArgumentException(name + " cannot be blank");
        }
        return result;
    }
}
