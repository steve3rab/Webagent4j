package io.webagent4j.locator.api;

import java.util.Objects;

/**
 * Immutable portable semantic reference backed by a Phase 2 locator definition.
 *
 * <p>The reference contains no page, browser handle, or backend object. It can therefore be
 * retained in a semantic observation and later resolved against an explicit current page context.
 *
 * @param definition semantic re-location intent
 */
public record ElementReference(LocatorDefinition definition) {

    /** Validates the immutable reference. */
    public ElementReference {
        Objects.requireNonNull(definition, "definition");
    }

    /** Resolves this reference against the supplied current context. */
    public <E> E resolve(ILocatorResolver<E> resolver) {
        return Objects.requireNonNull(resolver, "resolver").resolve(definition);
    }

    /**
     * Binds this portable reference to a context for an action that resolves immediately before
     * use.
     */
    public <E> IElementReference<E> bind(ILocatorResolver<E> resolver) {
        Objects.requireNonNull(resolver, "resolver");
        return () -> resolver.resolve(definition);
    }
}
