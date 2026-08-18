package io.webagent4j.browser.playwright;

import io.webagent4j.common.LocatorFailureClassifier;
import io.webagent4j.dom.IElement;
import io.webagent4j.locator.ILocatorEngine;
import io.webagent4j.locator.LocatorContext;
import io.webagent4j.locator.api.ILocatorScope;
import io.webagent4j.locator.api.LocatorDefinition;
import io.webagent4j.locator.api.TextMatch;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Shared typed-scope resolution logic reused by {@link PlaywrightFind} and {@link
 * PlaywrightLocator}.
 *
 * <p>Neither an explicit element scope nor a structured scope is resolved here at chain-build time
 * by its callers: {@link PlaywrightFind} and {@link PlaywrightLocator} only append a {@link
 * IPendingScope} to a single ordered list via {@link #append(List, IPendingScope)} and defer
 * resolution until a terminal operation calls {@link #resolvePendingScopes}, so a mixed chain of
 * explicit and structured scopes is always resolved in the exact order it was declared, and a
 * structured scope's definition is re-evaluated against the live DOM on every retry or replay
 * instead of being frozen into one concrete node.
 */
final class PlaywrightScopeResolver {

    private PlaywrightScopeResolver() {
        // not instantiable
    }

    /** Returns a copy of the context scoped to the supplied element. */
    static LocatorContext resolveElementScope(LocatorContext context, IElement scope) {
        Objects.requireNonNull(scope, "scope");
        return context.within(scope);
    }

    /** Returns a new immutable pending-scope list with {@code scope} appended. */
    static List<IPendingScope> append(List<IPendingScope> pending, IPendingScope scope) {
        List<IPendingScope> next = new ArrayList<>(pending);
        next.add(scope);
        return List.copyOf(next);
    }

    /**
     * Resolves every pending scope against {@code base}, strictly in declaration order: an explicit
     * element scope narrows the context immediately, a structured scope is re-resolved fresh
     * through {@link #resolveStructuredScope}, and each result becomes the starting context for the
     * next entry - exactly the order the caller wrote the {@code within(...)} chain in, never
     * regrouped by scope kind.
     */
    static LocatorContext resolvePendingScopes(
            ILocatorEngine engine, LocatorContext base, List<IPendingScope> pending) {
        LocatorContext resolved = base;
        for (IPendingScope scope : pending) {
            resolved =
                    switch (scope) {
                        case IPendingScope.Element element ->
                                resolveElementScope(resolved, element.element());
                        case IPendingScope.Structured structured ->
                                resolveStructuredScope(engine, resolved, structured.scope());
                    };
        }
        return resolved;
    }

    /**
     * Returns a copy of the context progressively narrowed by an explicit element scope followed by
     * every {@code containingText} constraint, applied in order.
     *
     * <p>Each constraint is resolved as a unique, unambiguous region inside the scope narrowed by
     * the previous constraint: {@code containingText("Laptop B").containingText("Available")} first
     * narrows to the "Laptop B" region, then narrows again to "Available" strictly inside that
     * region, not anywhere on the page. A constraint is a hard scope, not a scoring bonus, so an
     * ambiguous or unresolvable constraint fails explicitly instead of silently narrowing to the
     * wrong region.
     */
    static LocatorContext resolveStructuredScope(
            ILocatorEngine engine, LocatorContext context, ILocatorScope<IElement> scope) {
        Objects.requireNonNull(scope, "scope");
        LocatorContext next = context;
        if (scope.scopeElement().isPresent()) {
            next = next.within(scope.scopeElement().get());
        }
        List<String> containingText =
                Objects.requireNonNull(scope.containingText(), "scope.containingText()");
        for (String text : containingText) {
            if (text == null || text.isBlank()) {
                throw new IllegalArgumentException(
                        "scope.containingText() must not contain a null or blank value");
            }
            next = next.within(resolveContainer(engine, next, text));
        }
        return next;
    }

    /**
     * Resolves one unambiguous container matching {@code text}, preferring accessible-name evidence
     * (aria-label, aria-labelledby, etc.) and falling back to visible text only when
     * accessible-name resolution demonstrably reports a safe "not found" outcome.
     *
     * <p>The fallback is never triggered by ambiguity or by a genuine backend/runtime failure: both
     * are hard constraints that must propagate unchanged rather than being silently retried under a
     * different strategy.
     */
    private static IElement resolveContainer(
            ILocatorEngine engine, LocatorContext context, String text) {
        try {
            return engine.locateSingle(
                            context,
                            LocatorDefinition.element()
                                    .withAccessibleName(TextMatch.exactIgnoringCase(text)))
                    .element();
        } catch (RuntimeException accessibleFailure) {
            if (!LocatorFailureClassifier.isNotFound(accessibleFailure)) {
                throw accessibleFailure;
            }
            return engine.locateSingle(
                            context,
                            LocatorDefinition.element()
                                    .withVisibleText(TextMatch.exactIgnoringCase(text)))
                    .element();
        }
    }
}
