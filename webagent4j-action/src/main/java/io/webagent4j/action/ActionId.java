package io.webagent4j.action;

import java.util.Objects;
import java.util.UUID;

/**
 * Immutable correlation identifier shared by every event from one action.
 *
 * <p>{@link #create()} is the preferred default and generates an opaque random identifier. The
 * public constructor supports externally supplied or restored identifiers, which may appear in
 * diagnostics and recordings; callers must not place secrets or sensitive application data in an
 * action identifier.
 */
public record ActionId(String value) {

    /** Validates an externally supplied or restored action identifier. */
    public ActionId {
        value = Objects.requireNonNull(value, "value");
        if (value.isBlank()) {
            throw new IllegalArgumentException("value cannot be blank");
        }
    }

    /** Creates a new opaque random correlation identifier. */
    public static ActionId create() {
        return new ActionId(UUID.randomUUID().toString());
    }
}
