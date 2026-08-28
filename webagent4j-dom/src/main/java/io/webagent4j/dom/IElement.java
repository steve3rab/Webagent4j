package io.webagent4j.dom;

import io.webagent4j.extraction.api.ExtractionProvenance;
import io.webagent4j.extraction.api.ExtractionRequest;
import io.webagent4j.extraction.api.ExtractionResult;
import io.webagent4j.locator.api.ElementRole;
import io.webagent4j.locator.api.IFind;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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

    /** Returns the current live form-control value, or an empty string when not applicable. */
    default String value() {
        return attributes().getOrDefault("value", "");
    }

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

    /**
     * Returns whether this handle still refers to the exact same concrete, currently-attached
     * physical node it referred to when it was originally resolved - never merely to a different
     * node that happens to satisfy the same semantic query now.
     *
     * <p>This exists to close the window between a governed-execution policy authorizing a
     * specific, already-resolved target and that target's backend side effect: a caller that
     * authorized target T1 must never have its authorization silently transferred to a replacement
     * T2 that appeared after T1 was removed but before the side effect ran, even though both
     * equally match the same locator. Callers that need this guarantee call it immediately before
     * the side effect, after every other precondition and authorization has already passed, and
     * treat {@code false} as fail-closed: the side effect must not proceed.
     *
     * <p>The default implementation conservatively returns {@code true}: a backend that cannot
     * distinguish "the same physical node" from "an equally-matching replacement" makes no claim
     * either way, preserving this interface's existing best-effort re-resolution behavior. A
     * backend capable of tracking physical node identity must return {@code false} whenever that
     * identity cannot be proven to still hold - detachment, replacement, an inspection failure, or
     * malformed identity data are all treated as "not proven," never silently treated as "still the
     * same."
     */
    default boolean isStillTheOriginallyResolvedTarget() {
        return true;
    }

    /** Starts a semantic query scoped to this element's descendants. */
    default IFind<IElement> find() {
        throw new UnsupportedOperationException(
                "Scoped locators are not supported by this backend");
    }

    /**
     * Reads, converts, and validates {@code request}'s datum directly from this already-resolved
     * element - never a locator search, since the element is already the one the caller was told
     * was selected. Unlike {@code IPage#extract}/{@code IFrame#extract}, {@code request.source()}
     * is not searched for; it is retained only as descriptive metadata on the request and on the
     * returned {@link ExtractionResult#provenance()}, whose {@link
     * ExtractionProvenance#scopePath()} is empty here since no locator scope was resolved to reach
     * this element.
     *
     * @throws io.webagent4j.extraction.api.ExtractionAttributeMissingException if {@link
     *     ExtractionRequest#readType()} is {@code ATTRIBUTE} and this element lacks that attribute
     * @throws io.webagent4j.extraction.api.ExtractionConversionException if {@link
     *     ExtractionRequest#converter()} fails or returns {@code null}
     * @throws io.webagent4j.extraction.api.ExtractionValidationException if the converted value
     *     fails {@link ExtractionRequest#validator()}
     */
    default <T> ExtractionResult<T> extract(ExtractionRequest<T> request) {
        Objects.requireNonNull(request, "request");
        String raw =
                switch (request.readType()) {
                    case TEXT -> text();
                    case VALUE -> value();
                    case ATTRIBUTE -> {
                        String name = request.attributeName().orElseThrow();
                        String attributeValue = attributes().get(name);
                        if (attributeValue == null) {
                            throw new io.webagent4j.extraction.api
                                    .ExtractionAttributeMissingException(name);
                        }
                        yield attributeValue;
                    }
                };
        T value = request.convertAndValidate(raw);
        ExtractionProvenance provenance =
                new ExtractionProvenance(
                        List.of(), request.source(), request.readType(), request.attributeName());
        return new ExtractionResult<>(value, Optional.of(raw), provenance);
    }
}
