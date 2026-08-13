package io.webagent4j.dom;

/**
 * Immutable snapshot of the states that affect locator constraints and browser interaction.
 *
 * @param present whether the element currently exists in the DOM
 * @param visible whether the element is currently rendered as visible
 * @param enabled whether the element is enabled
 * @param editable whether the element accepts text editing
 * @param readOnly whether the element is read-only
 * @param checked whether a checkbox or radio control is checked
 * @param selected whether an option-like control is selected
 * @param focused whether the element owns document focus
 * @param inViewport whether any part of the element intersects the viewport
 * @param clickable whether the backend can reliably determine that a click can reach the element
 * @param covered whether another element covers the element's center point
 * @param interactabilityKnown whether clickable and covered were determined reliably
 */
public record ElementState(
        boolean present,
        boolean visible,
        boolean enabled,
        boolean editable,
        boolean readOnly,
        boolean checked,
        boolean selected,
        boolean focused,
        boolean inViewport,
        boolean clickable,
        boolean covered,
        boolean interactabilityKnown) {

    /** Rejects internally contradictory clickable states. */
    public ElementState {
        if (clickable && (!present || !visible || !enabled || !inViewport || covered)) {
            throw new IllegalArgumentException("a clickable element must be actionable");
        }
    }

    /** Creates a conservative state when a backend cannot inspect advanced interactability. */
    public static ElementState basic(boolean visible, boolean enabled, boolean inViewport) {
        return new ElementState(
                true,
                visible,
                enabled,
                false,
                false,
                false,
                false,
                false,
                inViewport,
                false,
                false,
                false);
    }

    /** Returns whether the element is hidden. */
    public boolean hidden() {
        return present && !visible;
    }

    /** Returns whether the element is disabled. */
    public boolean disabled() {
        return present && !enabled;
    }

    /** Returns whether the element is detached from the DOM. */
    public boolean detached() {
        return !present;
    }
}
