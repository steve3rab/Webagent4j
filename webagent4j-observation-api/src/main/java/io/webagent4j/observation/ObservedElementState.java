package io.webagent4j.observation;

import io.webagent4j.dom.ElementState;
import java.util.Objects;
import java.util.Optional;

/**
 * Compact immutable semantic state that reuses the Phase 2 interaction state and adds reliable
 * observation-only flags.
 *
 * @param interaction Phase 2 state snapshot
 * @param required whether a form field is required
 * @param expanded expanded state when the backend can determine it
 */
public record ObservedElementState(
        ElementState interaction, boolean required, Optional<Boolean> expanded) {

    /** Validates state values. */
    public ObservedElementState {
        Objects.requireNonNull(interaction, "interaction");
        Objects.requireNonNull(expanded, "expanded");
    }

    /** Returns whether the element is visible. */
    public boolean visible() {
        return interaction.visible();
    }

    /** Returns whether the element is enabled. */
    public boolean enabled() {
        return interaction.enabled();
    }

    /** Returns whether the element accepts text editing. */
    public boolean editable() {
        return interaction.editable();
    }

    /** Returns whether the element is checked. */
    public boolean checked() {
        return interaction.checked();
    }

    /** Returns whether the element is selected. */
    public boolean selected() {
        return interaction.selected();
    }

    /** Returns whether the element owns focus. */
    public boolean focused() {
        return interaction.focused();
    }

    /** Returns whether the element is read-only. */
    public boolean readOnly() {
        return interaction.readOnly();
    }
}
