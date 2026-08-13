package io.webagent4j.action;

import java.util.Objects;
import java.util.Set;

/** Immutable portable key and optional modifiers. */
public record KeyPress(PortableKey key, Set<KeyModifier> modifiers) {

    /** Defensively stores the key combination. */
    public KeyPress {
        Objects.requireNonNull(key, "key");
        modifiers = Set.copyOf(Objects.requireNonNull(modifiers, "modifiers"));
    }

    /** Creates a key press without modifiers. */
    public static KeyPress of(PortableKey key) {
        return new KeyPress(key, Set.of());
    }

    /** Creates a key press with one or more portable modifiers. */
    public static KeyPress of(PortableKey key, KeyModifier... modifiers) {
        return new KeyPress(key, Set.of(modifiers));
    }
}
