package io.webagent4j.browser.playwright;

import io.webagent4j.dom.IElement;
import io.webagent4j.locator.ILocatorEngine;
import io.webagent4j.locator.LocatorContext;
import io.webagent4j.locator.api.ILocatorScope;
import io.webagent4j.locator.api.LocatorDefinition;
import io.webagent4j.locator.api.TextMatch;
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
     * Returns a copy of the context narrowed by an explicit element scope, textual containment
     * constraints, or both, applied in order.
     */
    static LocatorContext resolveStructuredScope(
            ILocatorEngine engine, LocatorContext context, ILocatorScope<IElement> scope) {
        Objects.requireNonNull(scope, "scope");
        LocatorContext next = context;
        if (scope.scopeElement().isPresent()) {
            next = next.within(scope.scopeElement().get());
        }
        for (String text : scope.containingText()) {
            if (text == null || text.isBlank()) {
                continue;
            }
            // Prefer accessible name matching (aria-label, aria-labelledby, etc.) and fall back to
            // visible text when accessible name does not match anything.
            IElement container;
            try {
                container =
                        engine.locate(
                                        next,
                                        LocatorDefinition.element()
                                                .withAccessibleName(
                                                        TextMatch.exactIgnoringCase(text)))
                                .element();
            } catch (RuntimeException accessibleFailure) {
                // Try visible text as a fallback before giving up
                container =
                        engine.locate(
                                        next,
                                        LocatorDefinition.element()
                                                .withVisibleText(TextMatch.exactIgnoringCase(text)))
                                .element();
            }
            next = next.within(container);
            break;
        }
        return next;
    }
}
