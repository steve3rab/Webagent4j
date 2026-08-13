package io.webagent4j.action;

import java.util.Objects;
import java.util.UUID;

/** Immutable correlation identifier shared by every event from one action. */
public record ActionId(String value) {

    /** Validates an externally restored action identifier. */
    public ActionId {
        value = Objects.requireNonNull(value, "value");
        if (value.isBlank()) {
            throw new IllegalArgumentException("value cannot be blank");
        }
    }

    /** Creates a new random correlation identifier. */
    public static ActionId create() {
        return new ActionId(UUID.randomUUID().toString());
    }
}
