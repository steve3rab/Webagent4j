package io.webagent4j.browser.playwright;

import com.microsoft.playwright.ElementHandle;
import com.microsoft.playwright.Frame;
import com.microsoft.playwright.FrameLocator;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.TimeoutError;
import io.webagent4j.browser.FrameDefinition;
import io.webagent4j.common.LocatorException;
import io.webagent4j.common.LocatorFailureClassifier;
import io.webagent4j.dom.IElement;
import io.webagent4j.locator.AmbiguousLocatorException;
import io.webagent4j.locator.ILiveLocatorContext;
import io.webagent4j.locator.ILocatorEngine;
import io.webagent4j.locator.LocatorCandidate;
import io.webagent4j.locator.LocatorContext;
import io.webagent4j.locator.LocatorDiagnostics;
import io.webagent4j.locator.LocatorDiagnosticsLevel;
import io.webagent4j.locator.LocatorNotFoundException;
import io.webagent4j.locator.LocatorResult;
import io.webagent4j.locator.LocatorScope;
import io.webagent4j.locator.LocatorStrategyType;
import io.webagent4j.locator.api.ILocatorScope;
import io.webagent4j.locator.api.LocatorDefinition;
import io.webagent4j.locator.api.TextMatch;
import io.webagent4j.wait.IWaitProbe;
import io.webagent4j.wait.WaitBudget;
import io.webagent4j.wait.WaitEngine;
import io.webagent4j.wait.WaitInterruptedException;
import io.webagent4j.wait.WaitPolicy;
import io.webagent4j.wait.WaitResult;
import io.webagent4j.wait.WaitSample;
import io.webagent4j.wait.WaitStatus;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Shared typed-scope resolution logic used by Playwright find and locator chains. */
final class PlaywrightScopeResolver {

    private static final WaitEngine FRAME_WAIT_ENGINE = new WaitEngine();
    private static final Duration ONE_SHOT_TIMEOUT = Duration.ofNanos(1);

    private PlaywrightScopeResolver() {}

    static LocatorContext resolveElementScope(LocatorContext context, IElement scope) {
        Objects.requireNonNull(scope, "scope");
        Optional<IElement> currentRoot = context.scope().root();
        if (currentRoot.isPresent()) {
            requireDescendantOrSelf(currentRoot.get(), scope);
        }
        return withinResolvedElement(context, scope, "Explicit element");
    }

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

    static List<IPendingScope> append(List<IPendingScope> pending, IPendingScope scope) {
        List<IPendingScope> next = new ArrayList<>(pending);
        next.add(scope);
        return List.copyOf(next);
    }

    static LocatorContext resolvePendingScopes(
            ILocatorEngine engine, LocatorContext base, List<IPendingScope> pending) {
        return resolvePendingScopes(
                engine,
                base,
                pending,
                WaitBudget.start(
                        base.config().resolutionBudget().timeout(), FRAME_WAIT_ENGINE.clock()));
    }

    static LocatorContext resolvePendingScopes(
            ILocatorEngine engine,
            LocatorContext base,
            List<IPendingScope> pending,
            WaitBudget budget) {
        LocatorContext resolved = base;
        for (IPendingScope scope : pending) {
            resolved =
                    switch (scope) {
                        case IPendingScope.Element element ->
                                resolveElementScope(resolved, element.element());
                        case IPendingScope.Structured structured ->
                                resolveStructuredScope(
                                        engine, resolved, structured.scope(), budget);
                        case IPendingScope.Frame frame ->
                                resolveFrameScope(engine, resolved, frame.definition(), budget);
                    };
        }
        return resolved;
    }

    static LocatorContext resolveFrameScope(
            ILocatorEngine engine, LocatorContext context, FrameDefinition definition) {
        WaitBudget budget =
                WaitBudget.start(
                        context.config().resolutionBudget().timeout(), FRAME_WAIT_ENGINE.clock());
        return resolveFrameScope(engine, context, definition, budget);
    }

    private static LocatorContext resolveFrameScope(
            ILocatorEngine engine,
            LocatorContext context,
            FrameDefinition definition,
            WaitBudget budget) {
        LocatorDefinition query = LocatorDefinition.css(frameElementSelector(definition));
        WaitSample<FrameMatch> sample =
                probeFrameOnce(
                        engine, ILiveLocatorContext.fixed(context), definition, query, budget);
        if (sample.status() != WaitSample.Status.SATISFIED) {
            throw new LocatorNotFoundException("No frame matched " + describeFrame(definition));
        }
        LocatorResult iframe =
                toLocatorResult(query, sample.value().orElseThrow(), budget.elapsed());
        return descendIntoFrame(engine, iframe, definition, context);
    }

    static LocatorResult resolveFrameElement(
            ILocatorEngine engine,
            ILiveLocatorContext liveContext,
            FrameDefinition definition,
            Duration timeout) {
        return resolveFrameElement(engine, liveContext, definition, timeout, Optional.empty());
    }

    private static LocatorResult resolveFrameElement(
            ILocatorEngine engine,
            ILiveLocatorContext liveContext,
            FrameDefinition definition,
            Duration timeout,
            Optional<Duration> stability) {
        Objects.requireNonNull(definition, "definition");
        LocatorDefinition query = LocatorDefinition.css(frameElementSelector(definition));

        WaitBudget budget = WaitBudget.start(timeout, FRAME_WAIT_ENGINE.clock());
        WaitPolicy policy =
                WaitPolicy.pollingEvery(liveContext.baseline().config().pollingInterval());
        policy = stability.map(policy::withStableFor).orElse(policy);

        IWaitProbe<FrameMatch> probe =
                () -> probeFrameOnce(engine, liveContext, definition, query, budget);
        WaitResult<FrameMatch> waited;
        try {
            waited = FRAME_WAIT_ENGINE.await(budget, policy, probe);
        } catch (WaitInterruptedException interrupted) {
            throw new LocatorException("Frame wait was interrupted", interrupted);
        }
        if (waited.status() != WaitStatus.SUCCESS) {
            throw new LocatorNotFoundException(
                    "No frame matched "
                            + describeFrame(definition)
                            + " within "
                            + timeout.toMillis()
                            + " ms");
        }
        return toLocatorResult(query, waited.value().orElseThrow(), budget.elapsed());
    }

    private static WaitSample<FrameMatch> probeFrameOnce(
            ILocatorEngine engine,
            ILiveLocatorContext liveContext,
            FrameDefinition definition,
            LocatorDefinition query,
            WaitBudget budget) {
        LocatorContext context;
        try {
            context = liveContext.resolve();
        } catch (RuntimeException resolutionFailure) {
            if (!LocatorFailureClassifier.isNotFound(resolutionFailure)) {
                throw resolutionFailure;
            }
            return WaitSample.pending();
        }

        LocatorDefinition boundedQuery = query.withTimeout(atLeastOneNano(budget.remaining()));
        List<LocatorCandidate> matches =
                filterByUrl(engine.locateAll(context, boundedQuery), definition, budget);
        if (matches.size() > 1) {
            throw new AmbiguousLocatorException(
                    "Frame query "
                            + describeFrame(definition)
                            + " matched "
                            + matches.size()
                            + " frames");
        }
        if (matches.isEmpty()) {
            return WaitSample.pending();
        }
        LocatorCandidate winner = matches.getFirst();
        return WaitSample.satisfied(new FrameMatch(context, winner), winner.identity());
    }

    private static List<LocatorCandidate> filterByUrl(
            List<LocatorCandidate> candidates, FrameDefinition definition, WaitBudget budget) {
        if (definition.url().isEmpty()) {
            return candidates;
        }
        TextMatch match = definition.url().orElseThrow();
        List<LocatorCandidate> filtered = new ArrayList<>();
        for (int index = 0; index < candidates.size(); index++) {
            LocatorCandidate candidate = candidates.get(index);
            double inspectionTimeoutMillis =
                    PlaywrightLocatorBackend.operationTimeoutMillis(
                            atLeastOneNano(budget.remaining()), candidates.size() - index);
            if (matchesUrl(candidate.element(), match, inspectionTimeoutMillis)) {
                filtered.add(candidate);
            }
        }
        return List.copyOf(filtered);
    }

    private static Duration atLeastOneNano(Duration duration) {
        return duration.isZero() ? Duration.ofNanos(1) : duration;
    }

    private static LocatorResult toLocatorResult(
            LocatorDefinition query, FrameMatch match, Duration elapsed) {
        LocatorCandidate winner = match.candidate();
        LocatorDiagnostics diagnostics =
                new LocatorDiagnostics(
                        query,
                        match.context().config().resolutionPolicy(),
                        LocatorDiagnosticsLevel.BASIC,
                        match.context().scope().path(),
                        List.of(),
                        List.of(),
                        1,
                        0,
                        0,
                        List.of(),
                        winner.exactMatch() ? 1 : 0,
                        winner.exactMatch() ? 0 : 1,
                        Optional.of(winner),
                        elapsed,
                        false,
                        Set.of(),
                        Optional.empty(),
                        List.of());
        return new LocatorResult(
                query,
                winner.element(),
                winner.strategy(),
                winner.score(),
                winner.confidence(),
                winner.exactMatch(),
                List.of(winner),
                diagnostics);
    }

    private record FrameMatch(LocatorContext context, LocatorCandidate candidate) {}

    static LocatorResult resolveTerminalFrameElement(
            ILocatorEngine engine,
            LocatorContext baseContext,
            List<IPendingScope> parentPendingScopes,
            FrameDefinition definition,
            Duration timeout) {
        return resolveFrameElement(
                engine,
                liveContext(engine, baseContext, parentPendingScopes, timeout),
                definition,
                timeout,
                definition.stability());
    }

    static ILiveLocatorContext liveContext(
            ILocatorEngine engine, LocatorContext baseContext, List<IPendingScope> pendingScopes) {
        return liveContext(
                engine,
                baseContext,
                pendingScopes,
                baseContext.config().resolutionBudget().timeout());
    }

    static ILiveLocatorContext liveContext(
            ILocatorEngine engine,
            LocatorContext baseContext,
            List<IPendingScope> pendingScopes,
            Duration timeout) {
        return new ILiveLocatorContext() {
            private WaitBudget budget;

            @Override
            public LocatorContext baseline() {
                return baseContext;
            }

            @Override
            public LocatorContext resolve() {
                if (budget == null) {
                    budget = WaitBudget.start(timeout, FRAME_WAIT_ENGINE.clock());
                }
                return resolvePendingScopes(engine, baseContext, pendingScopes, budget);
            }
        };
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

    private static boolean matchesUrl(
            IElement iframeElement, TextMatch match, double inspectionTimeoutMillis) {
        Locator iframeLocator = PlaywrightLocatorBackend.unwrap(iframeElement);
        ElementHandle handle;
        try {
            if (!(inspectionTimeoutMillis > 0.0)) {
                return false;
            }
            List<ElementHandle> handles = iframeLocator.elementHandles();
            if (handles.isEmpty()) {
                return false;
            }
            handle = handles.getFirst();
        } catch (TimeoutError vanished) {
            if (confirmedAbsent(iframeLocator, vanished)) {
                return false;
            }
            throw vanished;
        } catch (PlaywrightException failure) {
            if (PlaywrightFailureClassifier.isFrameUnavailable(failure)) {
                return false;
            }
            throw failure;
        }

        Frame frame = handle.contentFrame();
        if (frame == null || frame.isDetached()) {
            return false;
        }
        String url = frame.url();
        return switch (match.type()) {
            case EXACT -> url.equals(match.value());
            case CASE_INSENSITIVE_EXACT -> url.equalsIgnoreCase(match.value());
            case CONTAINS ->
                    url.toLowerCase(java.util.Locale.ROOT)
                            .contains(match.value().toLowerCase(java.util.Locale.ROOT));
            case STARTS_WITH -> url.startsWith(match.value());
            case ENDS_WITH -> url.endsWith(match.value());
            case REGEX -> url.matches(match.value());
            case FUZZY -> throw new LocatorException("Frame URL matching does not support FUZZY");
        };
    }

    private static boolean confirmedAbsent(Locator locator, TimeoutError original) {
        try {
            return locator.count() == 0;
        } catch (PlaywrightException recheckFailure) {
            if (PlaywrightFailureClassifier.isFrameUnavailable(recheckFailure)) {
                return true;
            }
            original.addSuppressed(recheckFailure);
            throw original;
        } catch (RuntimeException recheckFailure) {
            original.addSuppressed(recheckFailure);
            throw original;
        }
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

    static LocatorContext resolveStructuredScope(
            ILocatorEngine engine, LocatorContext context, ILocatorScope<IElement> scope) {
        WaitBudget budget =
                WaitBudget.start(
                        context.config().resolutionBudget().timeout(), FRAME_WAIT_ENGINE.clock());
        return resolveStructuredScope(engine, context, scope, budget);
    }

    private static LocatorContext resolveStructuredScope(
            ILocatorEngine engine,
            LocatorContext context,
            ILocatorScope<IElement> scope,
            WaitBudget budget) {
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
            next =
                    withinResolvedElement(
                            next,
                            resolveContainer(engine, next, text, budget),
                            "Structured scope \"" + safeScopeText(text) + "\"");
        }
        return next;
    }

    private static LocatorContext withinResolvedElement(
            LocatorContext context, IElement element, String description) {
        if (!(context.backend() instanceof PlaywrightLocatorBackend)
                || !(element instanceof PlaywrightElement)) {
            return context.within(element);
        }
        return new LocatorContext(
                context.backend(), context.scope().within(element, description), context.config());
    }

    private static String safeScopeText(String value) {
        String normalized = value.replaceAll("[\\r\\n\\t]+", " ").trim();
        return normalized.length() <= 80 ? normalized : normalized.substring(0, 77) + "...";
    }

    private static IElement resolveContainer(
            ILocatorEngine engine, LocatorContext context, String text, WaitBudget budget) {
        if (context.backend() instanceof PlaywrightLocatorBackend backend) {
            try {
                return backend.resolveUniqueContainer(
                        context,
                        text,
                        LocatorStrategyType.ACCESSIBLE_NAME,
                        atLeastOneNano(budget.remaining()));
            } catch (RuntimeException accessibleFailure) {
                if (!LocatorFailureClassifier.isNotFound(accessibleFailure)) {
                    throw accessibleFailure;
                }
                return backend.resolveUniqueContainer(
                        context,
                        text,
                        LocatorStrategyType.VISIBLE_TEXT,
                        atLeastOneNano(budget.remaining()));
            }
        }

        try {
            engine.locateSingle(
                    context,
                    LocatorDefinition.element()
                            .withAttribute("aria-label", text)
                            .withTimeout(ONE_SHOT_TIMEOUT));
        } catch (RuntimeException directLabelFailure) {
            if (!LocatorFailureClassifier.isNotFound(directLabelFailure)) {
                throw directLabelFailure;
            }
        }

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
