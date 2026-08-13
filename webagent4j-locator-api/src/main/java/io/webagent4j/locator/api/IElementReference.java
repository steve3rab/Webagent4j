package io.webagent4j.locator.api;

/**
 * Re-resolvable element reference that preserves semantic intent instead of a permanent DOM handle.
 *
 * @param <E> backend-neutral element type
 */
@FunctionalInterface
public interface IElementReference<E> {

    /** Resolves the reference against the current document and returns a fresh live element. */
    E resolve();
}
