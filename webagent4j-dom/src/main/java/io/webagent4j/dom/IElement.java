package io.webagent4j.dom;

import io.webagent4j.locator.api.ElementRole;
import io.webagent4j.locator.api.IFind;
import java.util.Map;
import java.util.Optional;

/**
 * Backend-neutral live reference to an element query.
 *
 * <p>An element is bound to its page and is not thread-safe. Implementations must not promise that
 * a native DOM handle remains valid forever. Locator-backed implementations re-resolve their target
 * before state reads and important actions, so an equivalent element recreated after SPA, AJAX or
 * client-side navigation updates can still be reached. Snapshot values returned by methods such as
 * {@link #attributes()} remain snapshots and must not be cached as proof of future state. No
 * browser backend type leaks through this interface.
 */
public interface IElement {

    /** Returns the semantic role known when this handle was resolved. */
    ElementRole role();

    /** Returns the computed accessible name, or an empty string when the element has none. */
    String accessibleName();

    /** Returns visible text normalized by the browser backend. */
    String text();

    /** Returns the lowercase HTML tag name. */
    String tagName();

    /** Returns a defensive immutable snapshot of element attributes. */
    Map<String, String> attributes();

    /** Returns whether the element is currently visible. */
    boolean visible();

    /** Returns whether the element currently accepts interaction. */
    boolean enabled();

    /**
     * Returns one current state snapshot. Backends that cannot reliably inspect advanced
     * interactability must return {@code interactabilityKnown=false} rather than simulate support.
     */
    default ElementState state() {
        return ElementState.basic(visible(), enabled(), boundingBox().isPresent());
    }

    /** Returns whether the element currently exists in the DOM. */
    default boolean present() {
        return state().present();
    }

    /** Returns whether the element currently accepts text editing. */
    default boolean editable() {
        return state().editable();
    }

    /** Returns whether the element is read-only. */
    default boolean readOnly() {
        return state().readOnly();
    }

    /** Returns whether a checkbox or radio control is checked. */
    default boolean checked() {
        return state().checked();
    }

    /** Returns whether an option-like control is selected. */
    default boolean selected() {
        return state().selected();
    }

    /** Returns whether the element owns document focus. */
    default boolean focused() {
        return state().focused();
    }

    /** Returns whether any part of the element intersects the current viewport. */
    default boolean inViewport() {
        return state().inViewport();
    }

    /** Returns reliable clickability; visibility alone is never treated as clickability. */
    default boolean clickable() {
        return state().clickable();
    }

    /** Returns whether another element covers the target's center point. */
    default boolean covered() {
        return state().covered();
    }

    /** Returns the current bounding box when the element participates in layout. */
    Optional<BoundingBox> boundingBox();

    /** Performs the backend's normal click operation, including its native actionability checks. */
    void click();

    /** Starts a semantic query scoped to this element's descendants. */
    default IFind<IElement> find() {
        throw new UnsupportedOperationException(
                "Scoped locators are not supported by this backend");
    }
}
