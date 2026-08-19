package io.webagent4j.browser.playwright;

import com.microsoft.playwright.ElementHandle;
import com.microsoft.playwright.Frame;
import com.microsoft.playwright.FrameLocator;
import com.microsoft.playwright.Locator;
import io.webagent4j.browser.FrameDefinition;
import io.webagent4j.common.LocatorException;
import io.webagent4j.common.LocatorFailureClassifier;
import io.webagent4j.dom.IElement;
import io.webagent4j.locator.ILiveLocatorContext;
import io.webagent4j.locator.ILocatorEngine;
import io.webagent4j.locator.LocatorContext;
import io.webagent4j.locator.LocatorNotFoundException;
import io.webagent4j.locator.LocatorResult;
import io.webagent4j.locator.LocatorScope;
import io.webagent4j.locator.api.ILocatorScope;
import io.webagent4j.locator.api.LocatorDefinition;
import io.webagent4j.locator.api.TextMatch;
import java.time.Duration;
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
                        case IPendingScope.Frame frame ->
                                resolveFrameScope(engine, resolved, frame.definition());
                    };
        }
        return resolved;
    }

    /**
     * Resolves one frame boundary against the current live context, bounded to one immediate lookup
     * - see {@link #ONE_SHOT_TIMEOUT} - since this is always one hop inside an outer {@link
     * io.webagent4j.wait.WaitEngine}-driven poll (either another pending-scope resolution or {@link
     * PlaywrightFrameLocator}'s own retry loop for a later hop), never the wait itself.
     *
     * <p>A frame is a separate document boundary, never a descendant DOM element of the page that
     * contains its {@code <iframe>}: the returned context's backend is a brand-new {@link
     * PlaywrightLocatorBackend} rooted at the frame's own document, and its {@link LocatorScope}
     * starts a fresh chain (see {@link LocatorScope#frame}) rather than narrowing the current one.
     */
    static LocatorContext resolveFrameScope(
            ILocatorEngine engine, LocatorContext context, FrameDefinition definition) {
        LocatorResult iframe =
                resolveFrameElement(
                        engine, ILiveLocatorContext.fixed(context), definition, ONE_SHOT_TIMEOUT);
        return descendIntoFrame(engine, iframe, definition, context);
    }

    /**
     * Resolves the {@code <iframe>} element matching {@code definition} against {@code
     * liveContext}, with real {@link io.webagent4j.wait.WaitEngine}-driven waiting/retrying up to
     * {@code timeout} - used by {@link PlaywrightFrameLocator}'s own terminal operations, where the
     * frame being searched for is the actual target of the caller's wait, not a prerequisite hop.
     *
     * <p>Zero matches on id/name/title is a typed not-found outcome; two or more equally valid
     * matches is a typed ambiguous outcome - both reuse {@link ILocatorEngine}'s existing
     * classification for a {@link LocatorDefinition#css} query, since an {@code <iframe>} is
     * discovered the same deterministic way as any other element. A {@link FrameDefinition#url()}
     * criterion is checked only after id/name/title resolve to exactly one candidate: it narrows
     * that single resolved frame further (a mismatch is not found), rather than participating in
     * the id/name/title ambiguity check itself.
     *
     * <p>{@code timeout} is always what actually governs this call: {@link FrameDefinition#timeout}
     * is intentionally not consulted here, only by the caller that decides whether this is a
     * one-shot hop or the caller's own real wait target - see {@link #resolveFrameScope} versus
     * {@link PlaywrightFrameLocator}. Honoring the definition's own override unconditionally here
     * would let a nested frame hop's timeout leak into what must stay a bounded, non-retrying
     * probe, reintroducing exactly the nested full-timeout wait this split is designed to prevent.
     */
    static LocatorResult resolveFrameElement(
            ILocatorEngine engine,
            ILiveLocatorContext liveContext,
            FrameDefinition definition,
            Duration timeout) {
        return resolveFrameElement(engine, liveContext, definition, timeout, Optional.empty());
    }

    /**
     * As {@link #resolveFrameElement(ILocatorEngine, ILiveLocatorContext, FrameDefinition,
     * Duration)}, but additionally honors {@code stability} - always {@link Optional#empty()} for a
     * {@link #resolveFrameScope} prerequisite hop, since requiring continuous stability inherently
     * needs multiple polls over time and would defeat the one-shot, non-retrying probe that hop
     * must stay bounded to; only {@link #resolveTerminalFrameElement} (the real terminal wait
     * target) passes the definition's own {@link FrameDefinition#stability()} through.
     */
    private static LocatorResult resolveFrameElement(
            ILocatorEngine engine,
            ILiveLocatorContext liveContext,
            FrameDefinition definition,
            Duration timeout,
            Optional<Duration> stability) {
        Objects.requireNonNull(definition, "definition");
        String selector = frameElementSelector(definition);
        LocatorDefinition query = LocatorDefinition.css(selector).withTimeout(timeout);
        query = stability.map(query::stableFor).orElse(query);
        LocatorResult result = engine.locateSingle(liveContext, query);
        if (definition.url().isPresent()
                && !matchesUrl(result.element(), definition.url().orElseThrow())) {
            throw new LocatorNotFoundException(
                    "Frame matched by id/name/title but its current URL does not satisfy the"
                            + " requested URL criterion");
        }
        return result;
    }

    /**
     * Converts a resolved {@code <iframe>} element into a {@link LocatorContext} rooted at that
     * frame's own document, via Playwright's {@link Locator#contentFrame()} - a lazily-resolving
     * {@link FrameLocator}, not a frozen {@link Frame} snapshot, so every later query issued
     * through the returned context's backend re-locates the same {@code <iframe>} element and its
     * current content document fresh, on every real Playwright call. A removed-then-reinserted
     * {@code <iframe>} matching the same selector is followed transparently; one that no longer
     * matches, or now matches more than one element, surfaces as a typed not-found or ambiguous
     * failure the next time it is actually used - never a stale reference to the old document.
     */
    /**
     * Resolves {@code definition} as the terminal target of the caller's own wait: {@code
     * parentPendingScopes} (everything before this frame in the chain, if anything) is re-resolved
     * fresh on every retry through a live context - so a parent frame this frame lives inside is
     * re-verified on every attempt too, not resolved once before the wait begins - while {@code
     * definition} itself is searched for with real {@link io.webagent4j.wait.WaitEngine}-driven
     * waiting up to {@code timeout}. Used by both {@link PlaywrightFrame}'s own state reads (url,
     * title, navigate) and {@link PlaywrightFrameLocator}'s terminal operations.
     */
    static LocatorResult resolveTerminalFrameElement(
            ILocatorEngine engine,
            LocatorContext baseContext,
            List<IPendingScope> parentPendingScopes,
            FrameDefinition definition,
            Duration timeout) {
        ILiveLocatorContext liveContext =
                new ILiveLocatorContext() {
                    @Override
                    public LocatorContext baseline() {
                        return baseContext;
                    }

                    @Override
                    public LocatorContext resolve() {
                        return resolvePendingScopes(engine, baseContext, parentPendingScopes);
                    }
                };
        return resolveFrameElement(
                engine, liveContext, definition, timeout, definition.stability());
    }

    /**
     * Counts every currently-matching {@code <iframe>} element for {@code definition} - used only
     * by {@link PlaywrightFrameLocator#all()} to enumerate how many frames currently satisfy the
     * query. Unlike {@link #resolveFrameElement}, this never throws on more than one match:
     * ambiguity is exactly what this method is asked to count, not reject.
     */
    static List<IElement> resolveFrameElements(
            ILocatorEngine engine, LocatorContext context, FrameDefinition definition) {
        String selector = frameElementSelector(definition);
        LocatorDefinition query = LocatorDefinition.css(selector).withTimeout(ONE_SHOT_TIMEOUT);
        List<IElement> matches = new ArrayList<>();
        for (io.webagent4j.locator.LocatorCandidate candidate : engine.locateAll(context, query)) {
            IElement element = candidate.element();
            if (definition.url().isPresent()
                    && !matchesUrl(element, definition.url().orElseThrow())) {
                continue;
            }
            matches.add(element);
        }
        return List.copyOf(matches);
    }

    static LocatorContext descendIntoFrame(
            ILocatorEngine engine,
            LocatorResult iframe,
            FrameDefinition definition,
            LocatorContext parent) {
        Locator iframeLocator = PlaywrightLocatorBackend.unwrap(iframe.element());
        FrameLocator frameLocator = iframeLocator.contentFrame();
        Locator documentRoot = frameLocator.locator("html");
        PlaywrightLocatorBackend backend =
                new PlaywrightLocatorBackend(
                        documentRoot,
                        engine,
                        parent.config(),
                        LocatorScope.frame(describeFrame(definition)));
        return backend.context();
    }

    private static boolean matchesUrl(IElement iframeElement, TextMatch match) {
        Locator iframeLocator = PlaywrightLocatorBackend.unwrap(iframeElement);
        ElementHandle handle = iframeLocator.elementHandle();
        Frame frame = handle.contentFrame();
        if (frame == null) {
            return false;
        }
        String url = frame.url();
        return switch (match.type()) {
            case EXACT -> url.equals(match.value());
            case CASE_INSENSITIVE_EXACT -> url.equalsIgnoreCase(match.value());
            case CONTAINS, FUZZY ->
                    url.toLowerCase(java.util.Locale.ROOT)
                            .contains(match.value().toLowerCase(java.util.Locale.ROOT));
            case STARTS_WITH -> url.startsWith(match.value());
            case ENDS_WITH -> url.endsWith(match.value());
            case REGEX -> url.matches(match.value());
        };
    }

    private static String frameElementSelector(FrameDefinition definition) {
        StringBuilder selector = new StringBuilder("iframe");
        definition
                .id()
                .ifPresent(
                        id ->
                                selector.append(
                                        PlaywrightLocatorBackend.attributeSelector("id", id)));
        definition
                .name()
                .ifPresent(match -> selector.append(frameAttributeSelector("name", match)));
        definition
                .title()
                .ifPresent(match -> selector.append(frameAttributeSelector("title", match)));
        return selector.toString();
    }

    /**
     * Builds a CSS attribute selector for one frame id/name/title criterion. Only exact and
     * case-insensitive-exact criteria are supported here - the only two {@link TextMatch} kinds
     * {@link io.webagent4j.browser.FrameDefinition#named} and {@link
     * io.webagent4j.browser.FrameDefinition#withTitle} can actually produce - so any other kind
     * fails explicitly rather than being silently dropped from the selector, which would otherwise
     * match a broader, unintended set of frames.
     */
    private static String frameAttributeSelector(String attribute, TextMatch match) {
        String escaped = match.value().replace("\\", "\\\\").replace("\"", "\\\"");
        String caseFlag =
                switch (match.type()) {
                    case EXACT -> "";
                    case CASE_INSENSITIVE_EXACT -> " i";
                    default ->
                            throw new LocatorException(
                                    "Frame "
                                            + attribute
                                            + " matching only supports exact or"
                                            + " case-insensitive-exact criteria, not "
                                            + match.type());
                };
        return "[" + attribute + "=\"" + escaped + "\"" + caseFlag + "]";
    }

    private static String describeFrame(FrameDefinition definition) {
        StringBuilder description = new StringBuilder("Frame[");
        List<String> parts = new ArrayList<>();
        definition.id().ifPresent(id -> parts.add("id=\"" + id + "\""));
        definition.name().ifPresent(match -> parts.add("name=\"" + match.value() + "\""));
        definition.title().ifPresent(match -> parts.add("title=\"" + match.value() + "\""));
        definition.url().ifPresent(match -> parts.add("url=\"" + match.value() + "\""));
        description.append(parts.isEmpty() ? "any" : String.join(", ", parts));
        description.append(']');
        return description.toString();
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
     * The timeout every container lookup here is bounded to: exactly one immediate DOM check, no
     * internal retrying of its own. A structured scope is always resolved from inside an outer
     * {@code WaitEngine}-driven poll (see {@code PlaywrightLocator}'s live context), which already
     * supplies the retry cadence for the whole logical wait; a container lookup that retried on its
     * own too would start a second, nested full-timeout wait inside a single outer poll attempt and
     * silently multiply the caller's configured timeout. {@link ILocatorEngine#locateSingle} still
     * guarantees exactly one immediate probe even against an already-expired budget, so a
     * momentarily-absent or newly-ambiguous container is still detected on this one attempt.
     */
    private static final Duration ONE_SHOT_TIMEOUT = Duration.ofNanos(1);

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
                                    .withAccessibleName(TextMatch.exactIgnoringCase(text))
                                    .withTimeout(ONE_SHOT_TIMEOUT))
                    .element();
        } catch (RuntimeException accessibleFailure) {
            if (!LocatorFailureClassifier.isNotFound(accessibleFailure)) {
                throw accessibleFailure;
            }
            return engine.locateSingle(
                            context,
                            LocatorDefinition.element()
                                    .withVisibleText(TextMatch.exactIgnoringCase(text))
                                    .withTimeout(ONE_SHOT_TIMEOUT))
                    .element();
        }
    }
}
