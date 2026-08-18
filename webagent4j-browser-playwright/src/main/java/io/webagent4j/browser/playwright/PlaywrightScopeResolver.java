package io.webagent4j.browser.playwright;

import io.webagent4j.common.LocatorFailureClassifier;
import io.webagent4j.dom.IElement;
import io.webagent4j.locator.ILocatorEngine;
import io.webagent4j.locator.LocatorContext;
import io.webagent4j.locator.api.ILocatorScope;
import io.webagent4j.locator.api.LocatorDefinition;
import io.webagent4j.locator.api.TextMatch;
import java.util.List;
import java.util.Objects;

/**
 * Shared typed-scope resolution logic reused by {@link PlaywrightFind} and {@link
 * PlaywrightLocator}.
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
