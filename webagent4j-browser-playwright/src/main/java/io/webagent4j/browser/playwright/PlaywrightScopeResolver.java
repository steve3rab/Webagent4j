package io.webagent4j.browser.playwright;

import com.microsoft.playwright.ElementHandle;
import com.microsoft.playwright.Locator;
import io.webagent4j.common.LocatorException;
import io.webagent4j.common.LocatorFailureClassifier;
import io.webagent4j.dom.IElement;
import io.webagent4j.locator.ILocatorEngine;
import io.webagent4j.locator.LocatorContext;
import io.webagent4j.locator.LocatorNotFoundException;
import io.webagent4j.locator.api.ILocatorScope;
import io.webagent4j.locator.api.LocatorDefinition;
import io.webagent4j.locator.api.TextMatch;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

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

    /**
     * Returns a copy of the context scoped to the supplied element, after proving - if the context
     * already has an element root - that the supplied element is a descendant of, or the same node
     * as, that root.
     *
     * <p>{@code within(...)} is a conjunction of nested constraints, never a replacement: {@code
     * within(A).within(B)} means "B, and B is inside A", not "B, regardless of A". When the context
     * is still unscoped (the page root, nothing narrowed it yet), the supplied element simply
     * becomes the first narrowing scope - there is no parent to prove membership against.
     * Otherwise, containment is proven against the real DOM relationship, never inferred from
     * diagnostics (accessible name, role, {@link io.webagent4j.locator.LocatorScope#path()}, or any
     * other human-readable description) - those describe the scope for logging, they do not
     * establish it.
     *
     * @throws LocatorNotFoundException if the current root or the supplied element is detached, or
     *     if the supplied element exists but is not inside the current root
     * @throws LocatorException if the supplied element cannot be validated against the current
     *     backend (a different {@link IElement} implementation, a different page, or a different
     *     browser context)
     */
    static LocatorContext resolveElementScope(LocatorContext context, IElement scope) {
        Objects.requireNonNull(scope, "scope");
        Optional<IElement> currentRoot = context.scope().root();
        if (currentRoot.isPresent()) {
            requireDescendantOrSelf(currentRoot.get(), scope);
        }
        return context.within(scope);
    }

    /**
     * Proves {@code child} is a descendant of, or the same node as, {@code parent} using the real
     * Playwright DOM relationship - never accessible name, role, or any diagnostic label. Presence
     * is checked with a synchronous {@link Locator#count()} first, deliberately avoiding an
     * implicit wait: a scope that has become detached must fail immediately as "not found", not be
     * silently retried or reinterpreted once its element handle is requested. Any exception this
     * method lets through after that presence check is a genuine backend/runtime failure, not a
     * "not found" or "ambiguous" outcome, and must propagate unchanged.
     */
    private static void requireDescendantOrSelf(IElement parent, IElement child) {
        if (!(parent instanceof PlaywrightElement playwrightParent)
                || !(child instanceof PlaywrightElement playwrightChild)) {
            throw new LocatorException(
                    "Explicit element scope belongs to a different browser backend and cannot be"
                            + " proven to be inside the current scope");
        }
        Locator parentLocator = playwrightParent.locator();
        Locator childLocator = playwrightChild.locator();
        if (parentLocator.count() == 0) {
            throw new LocatorNotFoundException(
                    "The current scope element is detached from the document");
        }
        if (childLocator.count() == 0) {
            throw new LocatorNotFoundException(
                    "Explicit element scope is detached from the document");
        }
        ElementHandle parentHandle = parentLocator.elementHandle();
        Object descendantOrSelf =
                childLocator.evaluate(
                        PlaywrightDomInspectionScripts.DESCENDANT_OR_SELF_FUNCTION, parentHandle);
        if (!Boolean.TRUE.equals(descendantOrSelf)) {
            throw new LocatorNotFoundException(
                    "Explicit element scope does not belong to the current scope: within(...)"
                            + " narrows the previous scope, an unrelated element cannot be"
                            + " substituted for it");
        }
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
            next = resolveElementScope(next, scope.scopeElement().get());
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
