package io.webagent4j.locator.api;

import java.time.Duration;
import java.util.List;

/**
 * Fluent immutable query that resolves elements only at a terminal operation.
 *
 * <p>{@link #first()} accepts multiple compatible candidates and returns the highest ranked one.
 * {@link #single()} additionally requires the best candidate to be sufficiently unique. {@link
 * #all()} returns every compatible candidate after filtering, deduplication and deterministic
 * ranking.
 */
public interface ILocator<E> {
    /** Constrains the query to an exact, case-insensitive accessible name. */
    ILocator<E> named(String name);

    /** Constrains the query to an accessible name containing the supplied text. */
    ILocator<E> nameContaining(String text);

    /** Constrains the query using conservative non-AI fuzzy name matching. */
    ILocator<E> fuzzyName(String name);

    /** Constrains a form control to an exact, case-insensitive associated label. */
    ILocator<E> labelled(String label);

    /** Filters out candidates that are not visible. */
    ILocator<E> visible();

    /** Filters out candidates that are visible. */
    ILocator<E> hidden();

    /** Filters out candidates that are not enabled. */
    ILocator<E> enabled();

    /** Filters out candidates that are enabled. */
    ILocator<E> disabled();

    /** Filters out candidates that are not editable. */
    ILocator<E> editable();

    /** Filters out candidates that are not read-only. */
    ILocator<E> readonly();

    /** Filters out candidates that are not checked. */
    ILocator<E> checked();

    /** Filters out candidates that are not selected. */
    ILocator<E> selected();

    /** Filters out candidates that do not currently own document focus. */
    ILocator<E> focused();

    /** Filters out candidates that are outside the current viewport. */
    ILocator<E> inViewport();

    /** Filters out candidates that cannot reliably receive a click. */
    ILocator<E> clickable();

    /** Filters out candidates that are not covered by another element. */
    ILocator<E> covered();

    /** Alias for an enabled hard constraint, useful when expressing negative intent. */
    default ILocator<E> notDisabled() {
        return enabled();
    }

    /** Overrides the positive maximum time allowed for resolution. */
    ILocator<E> timeout(Duration timeout);

    /** Makes resolution wait until a matching visible candidate exists. */
    ILocator<E> waitUntilVisible();

    /**
     * Requires the selected candidate identity and requested state to remain stable continuously
     * for the supplied positive duration before resolution succeeds.
     */
    ILocator<E> stableFor(Duration duration);

    /**
     * Returns a reusable semantic reference. Each {@link IElementReference#resolve()} operation
     * executes the locator again against the current DOM.
     */
    IElementReference<E> reference();

    /** Returns the highest-ranked compatible candidate or fails when none exists. */
    E first();

    /** Returns one unambiguous candidate or fails for zero or equivalent best matches. */
    E single();

    /** Returns all compatible candidates in deterministic score and DOM order. */
    List<E> all();
}
