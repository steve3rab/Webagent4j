package io.webagent4j.locator.api;

/**
 * Backend-neutral context able to resolve an immutable locator definition.
 *
 * @param <E> resolved element type
 */
@FunctionalInterface
public interface ILocatorResolver<E> {

    /** Resolves the definition against the current document state. */
    E resolve(LocatorDefinition definition);
}
