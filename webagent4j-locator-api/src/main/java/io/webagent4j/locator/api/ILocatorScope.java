package io.webagent4j.locator.api;

import java.util.List;
import java.util.Optional;

/**
 * Backend-neutral, typed semantic scope that narrows a locator's candidate universe before target
 * matching runs.
 *
 * <p>A scope is a hard constraint, not a scoring bonus: a missing or ambiguous scope must block
 * resolution explicitly instead of silently selecting the wrong candidate. Scopes are generic over
 * the same element type {@code E} as the {@link ILocator} or {@link IFind} they narrow, so a caller
 * can never pass an unrelated object where a semantic scope is expected — unlike an untyped {@code
 * Object} parameter, this is checked at compile time.
 */
public interface ILocatorScope<E> {

    /** Returns the element the scope should be narrowed to, when one is set. */
    Optional<E> scopeElement();

    /**
     * Returns ordered semantic text constraints, each resolved to a containing region before target
     * matching runs.
     */
    List<String> containingText();
}
