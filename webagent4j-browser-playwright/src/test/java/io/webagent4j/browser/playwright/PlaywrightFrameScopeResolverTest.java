package io.webagent4j.browser.playwright;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatRuntimeException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.microsoft.playwright.ElementHandle;
import com.microsoft.playwright.Frame;
import com.microsoft.playwright.FrameLocator;
import com.microsoft.playwright.Locator;
import io.webagent4j.browser.FrameDefinition;
import io.webagent4j.common.LocatorException;
import io.webagent4j.dom.IElement;
import io.webagent4j.locator.AmbiguousLocatorException;
import io.webagent4j.locator.ILiveLocatorContext;
import io.webagent4j.locator.ILocatorBackend;
import io.webagent4j.locator.ILocatorEngine;
import io.webagent4j.locator.LocatorCandidate;
import io.webagent4j.locator.LocatorConfig;
import io.webagent4j.locator.LocatorContext;
import io.webagent4j.locator.LocatorDiagnostics;
import io.webagent4j.locator.LocatorDiagnosticsLevel;
import io.webagent4j.locator.LocatorNotFoundException;
import io.webagent4j.locator.LocatorResolutionPolicy;
import io.webagent4j.locator.LocatorResult;
import io.webagent4j.locator.LocatorScope;
import io.webagent4j.locator.LocatorScopeType;
import io.webagent4j.locator.LocatorStrategyType;
import io.webagent4j.locator.api.ElementRole;
import io.webagent4j.locator.api.LocatorDefinition;
import io.webagent4j.locator.api.TextMatch;
import io.webagent4j.locator.api.TextMatchType;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Unit-level proof of {@link PlaywrightScopeResolver}'s frame-specific resolution contracts: the
 * same 0/1/N -> not-found/success/ambiguous classification element resolution already uses, a
 * document-boundary chain that starts fresh rather than nesting under the parent path, declaration
 * order preserved across mixed element/frame scopes, and the one-shot-versus-real-timeout split
 * that keeps a prerequisite frame hop from silently multiplying an outer wait's budget.
 */
class PlaywrightFrameScopeResolverTest {

    @Test
    void singleMatchWithNoUrlCriterionResolvesDirectly() {
        ILocatorEngine engine = mock(ILocatorEngine.class);
        LocatorContext context = pageContext();
        IElement iframe = frameElement("https://example.com/checkout");
        when(engine.locateSingle(resolvesTo(context), any())).thenReturn(result(iframe));

        LocatorResult resolved =
                PlaywrightScopeResolver.resolveFrameElement(
                        engine,
                        ILiveLocatorContext.fixed(context),
                        FrameDefinition.frame().named("checkout"),
                        Duration.ofMillis(250));

        assertThat(resolved.element()).isSameAs(iframe);
    }

    @Test
    void zeroMatchesPropagatesTheTypedNotFoundInstanceUnchanged() {
        ILocatorEngine engine = mock(ILocatorEngine.class);
        LocatorContext context = pageContext();
        LocatorNotFoundException notFound = new LocatorNotFoundException("no iframe matched");
        when(engine.locateSingle(resolvesTo(context), any())).thenThrow(notFound);

        assertThatRuntimeException()
                .isThrownBy(
                        () ->
                                PlaywrightScopeResolver.resolveFrameElement(
                                        engine,
                                        ILiveLocatorContext.fixed(context),
                                        FrameDefinition.frame().named("checkout"),
                                        Duration.ofMillis(250)))
                .isSameAs(notFound);
    }

    @Test
    void multipleMatchesPropagatesTheTypedAmbiguousInstanceUnchanged() {
        ILocatorEngine engine = mock(ILocatorEngine.class);
        LocatorContext context = pageContext();
        AmbiguousLocatorException ambiguous =
                new AmbiguousLocatorException("two frames named \"payment\"");
        when(engine.locateSingle(resolvesTo(context), any())).thenThrow(ambiguous);

        assertThatRuntimeException()
                .isThrownBy(
                        () ->
                                PlaywrightScopeResolver.resolveFrameElement(
                                        engine,
                                        ILiveLocatorContext.fixed(context),
                                        FrameDefinition.frame().named("payment"),
                                        Duration.ofMillis(250)))
                .isSameAs(ambiguous);
    }

    @Test
    void aGenuineBackendFailureIsNeverReclassifiedAsNotFoundOrAmbiguous() {
        ILocatorEngine engine = mock(ILocatorEngine.class);
        LocatorContext context = pageContext();
        RuntimeException backendFailure = new IllegalStateException("browser disconnected");
        when(engine.locateSingle(resolvesTo(context), any())).thenThrow(backendFailure);

        assertThatRuntimeException()
                .isThrownBy(
                        () ->
                                PlaywrightScopeResolver.resolveFrameElement(
                                        engine,
                                        ILiveLocatorContext.fixed(context),
                                        FrameDefinition.frame().named("payment"),
                                        Duration.ofMillis(250)))
                .isSameAs(backendFailure);
    }

    @Test
    void aUrlCriterionNarrowsAnAlreadyResolvedSingleFrameRatherThanParticipatingInAmbiguity() {
        ILocatorEngine engine = mock(ILocatorEngine.class);
        LocatorContext context = pageContext();
        IElement iframe = frameElement("https://example.com/checkout");
        when(engine.locateSingle(resolvesTo(context), any())).thenReturn(result(iframe));
        FrameDefinition definition =
                FrameDefinition.frame()
                        .named("checkout")
                        .withUrl(TextMatch.exact("https://example.com/checkout"));

        LocatorResult resolved =
                PlaywrightScopeResolver.resolveFrameElement(
                        engine,
                        ILiveLocatorContext.fixed(context),
                        definition,
                        Duration.ofMillis(250));

        assertThat(resolved.element()).isSameAs(iframe);
    }

    @Test
    void aMismatchingUrlCriterionOnAnOtherwiseUniqueMatchIsATypedNotFound() {
        ILocatorEngine engine = mock(ILocatorEngine.class);
        LocatorContext context = pageContext();
        IElement iframe = frameElement("https://example.com/other");
        when(engine.locateSingle(resolvesTo(context), any())).thenReturn(result(iframe));
        FrameDefinition definition =
                FrameDefinition.frame()
                        .named("checkout")
                        .withUrl(TextMatch.exact("https://example.com/checkout"));

        assertThatRuntimeException()
                .isThrownBy(
                        () ->
                                PlaywrightScopeResolver.resolveFrameElement(
                                        engine,
                                        ILiveLocatorContext.fixed(context),
                                        definition,
                                        Duration.ofMillis(250)))
                .isInstanceOf(LocatorNotFoundException.class);
    }

    @Test
    void aDetachedContentDocumentFailsTheUrlCriterionInsteadOfThrowingOrMatching() {
        ILocatorEngine engine = mock(ILocatorEngine.class);
        LocatorContext context = pageContext();
        Locator iframeLocator = mock(Locator.class);
        ElementHandle handle = mock(ElementHandle.class);
        when(iframeLocator.elementHandle()).thenReturn(handle);
        when(handle.contentFrame()).thenReturn(null);
        IElement iframe =
                new PlaywrightElement(
                        iframeLocator,
                        ElementRole.UNKNOWN,
                        null,
                        LocatorScope.page(),
                        LocatorConfig.builder().build());
        when(engine.locateSingle(resolvesTo(context), any())).thenReturn(result(iframe));
        FrameDefinition definition =
                FrameDefinition.frame()
                        .named("checkout")
                        .withUrl(TextMatch.exact("https://example.com/checkout"));

        assertThatRuntimeException()
                .isThrownBy(
                        () ->
                                PlaywrightScopeResolver.resolveFrameElement(
                                        engine,
                                        ILiveLocatorContext.fixed(context),
                                        definition,
                                        Duration.ofMillis(250)))
                .isInstanceOf(LocatorNotFoundException.class);
    }

    @Test
    void theCallerSuppliedTimeoutGovernsTheQueryNeverTheDefinitionsOwnOverride() {
        ILocatorEngine engine = mock(ILocatorEngine.class);
        LocatorContext context = pageContext();
        IElement iframe = frameElement("https://example.com/checkout");
        ArgumentCaptor<LocatorDefinition> captor = ArgumentCaptor.forClass(LocatorDefinition.class);
        when(engine.locateSingle(resolvesTo(context), captor.capture())).thenReturn(result(iframe));
        FrameDefinition definition =
                FrameDefinition.frame().named("checkout").withTimeout(Duration.ofSeconds(99));

        PlaywrightScopeResolver.resolveFrameElement(
                engine, ILiveLocatorContext.fixed(context), definition, Duration.ofMillis(250));

        assertThat(captor.getValue().timeout()).contains(Duration.ofMillis(250));
        assertThat(captor.getValue().css()).contains("iframe[name=\"checkout\" i]");
    }

    @Test
    void resolveFrameScopeBoundsThePrerequisiteHopToAOneShotTimeout() {
        ILocatorEngine engine = mock(ILocatorEngine.class);
        LocatorContext context = pageContext();
        IElement iframe = descendableFrameElement();
        ArgumentCaptor<LocatorDefinition> captor = ArgumentCaptor.forClass(LocatorDefinition.class);
        when(engine.locateSingle(resolvesTo(context), captor.capture())).thenReturn(result(iframe));

        LocatorContext resolved =
                PlaywrightScopeResolver.resolveFrameScope(
                        engine, context, FrameDefinition.frame().named("checkout"));

        assertThat(captor.getValue().timeout()).contains(Duration.ofNanos(1));
        assertThat(resolved.scope().type()).isEqualTo(LocatorScopeType.FRAME);
        assertThat(resolved.scope().root()).isEmpty();
        assertThat(resolved.scope().path()).containsExactly("Frame[name=\"checkout\"]");
    }

    @Test
    void resolveTerminalFrameElementUsesTheRealCallerSuppliedTimeoutNotAOneShotProbe() {
        ILocatorEngine engine = mock(ILocatorEngine.class);
        LocatorContext context = pageContext();
        IElement iframe = frameElement("https://example.com/checkout");
        ArgumentCaptor<LocatorDefinition> captor = ArgumentCaptor.forClass(LocatorDefinition.class);
        when(engine.locateSingle(resolvesTo(context), captor.capture())).thenReturn(result(iframe));

        PlaywrightScopeResolver.resolveTerminalFrameElement(
                engine,
                context,
                List.of(),
                FrameDefinition.frame().named("checkout"),
                Duration.ofSeconds(5));

        assertThat(captor.getValue().timeout()).contains(Duration.ofSeconds(5));
    }

    @Test
    void resolveFrameElementsCountsEveryCurrentMatchWithoutThrowingOnMoreThanOne() {
        ILocatorEngine engine = mock(ILocatorEngine.class);
        LocatorContext context = pageContext();
        IElement first = frameElement("https://example.com/a");
        IElement second = frameElement("https://example.com/b");
        when(engine.locateAll(eq(context), any()))
                .thenReturn(List.of(candidate(first), candidate(second)));

        List<IElement> matches =
                PlaywrightScopeResolver.resolveFrameElements(
                        engine, context, FrameDefinition.frame().named("payment"));

        assertThat(matches).containsExactly(first, second);
    }

    @Test
    void resolveFrameElementsFiltersOutCandidatesFailingTheUrlCriterion() {
        ILocatorEngine engine = mock(ILocatorEngine.class);
        LocatorContext context = pageContext();
        IElement matching = frameElement("https://example.com/checkout");
        IElement notMatching = frameElement("https://example.com/other");
        when(engine.locateAll(eq(context), any()))
                .thenReturn(List.of(candidate(matching), candidate(notMatching)));
        FrameDefinition definition =
                FrameDefinition.frame().withUrl(TextMatch.exact("https://example.com/checkout"));

        List<IElement> matches =
                PlaywrightScopeResolver.resolveFrameElements(engine, context, definition);

        assertThat(matches).containsExactly(matching);
    }

    @Test
    void resolveFrameElementsUsesAOneShotTimeoutRatherThanARetryingWait() {
        ILocatorEngine engine = mock(ILocatorEngine.class);
        LocatorContext context = pageContext();
        ArgumentCaptor<LocatorDefinition> captor = ArgumentCaptor.forClass(LocatorDefinition.class);
        when(engine.locateAll(eq(context), captor.capture())).thenReturn(List.of());

        PlaywrightScopeResolver.resolveFrameElements(
                engine, context, FrameDefinition.frame().named("payment"));

        assertThat(captor.getValue().timeout()).contains(Duration.ofNanos(1));
    }

    @Test
    void
            aNameCriterionOutsideExactOrCaseInsensitiveExactFailsClosedInsteadOfBeingSilentlyDropped() {
        ILocatorEngine engine = mock(ILocatorEngine.class);
        LocatorContext context = pageContext();
        FrameDefinition definition =
                new FrameDefinition(
                        Optional.empty(),
                        Optional.of(new TextMatch(TextMatchType.CONTAINS, "check")),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty());

        assertThatRuntimeException()
                .isThrownBy(
                        () ->
                                PlaywrightScopeResolver.resolveFrameElement(
                                        engine,
                                        ILiveLocatorContext.fixed(context),
                                        definition,
                                        Duration.ofMillis(250)))
                .isInstanceOf(LocatorException.class);
        verify(engine, never()).locateSingle(any(ILiveLocatorContext.class), any());
        verify(engine, never()).locateSingle(any(LocatorContext.class), any());
    }

    @Test
    void
            nestedFramesResolveInDeclarationOrderEachAgainstThePriorFramesOwnDocumentNotTheOriginalBase() {
        ILocatorEngine engine = mock(ILocatorEngine.class);
        LocatorContext base = pageContext();
        IElement outerIframe = descendableFrameElement();
        IElement innerIframe = descendableFrameElement();
        ArgumentCaptor<ILiveLocatorContext> liveContextCaptor =
                ArgumentCaptor.forClass(ILiveLocatorContext.class);
        // Both hops go through the same mocked method; distinguishing them requires inspecting
        // what each live context actually resolves to, not which stub matched first.
        when(engine.locateSingle(liveContextCaptor.capture(), any()))
                .thenAnswer(
                        invocation -> {
                            ILiveLocatorContext live = invocation.getArgument(0);
                            return base.equals(live.resolve())
                                    ? result(outerIframe)
                                    : result(innerIframe);
                        });

        List<IPendingScope> pending =
                PlaywrightScopeResolver.append(
                        PlaywrightScopeResolver.append(
                                List.of(),
                                new IPendingScope.Frame(FrameDefinition.frame().named("outer"))),
                        new IPendingScope.Frame(FrameDefinition.frame().named("inner")));

        LocatorContext resolved =
                PlaywrightScopeResolver.resolvePendingScopes(engine, base, pending);

        assertThat(resolved.scope().type()).isEqualTo(LocatorScopeType.FRAME);
        assertThat(resolved.scope().path()).containsExactly("Frame[name=\"inner\"]");
        List<LocatorContext> capturedContexts =
                liveContextCaptor.getAllValues().stream()
                        .map(ILiveLocatorContext::resolve)
                        .toList();
        assertThat(capturedContexts).anyMatch(candidate -> !candidate.equals(base));
    }

    @Test
    void anExplicitElementScopeDeclaredBeforeAFrameNarrowsWhereTheFrameIsSearchedFor() {
        ILocatorEngine engine = mock(ILocatorEngine.class);
        LocatorContext base = pageContext();
        IElement explicitScopeElement =
                new PlaywrightElement(
                        mock(Locator.class),
                        ElementRole.UNKNOWN,
                        null,
                        LocatorScope.page(),
                        LocatorConfig.builder().build());
        LocatorContext scopedContext = base.within(explicitScopeElement);
        IElement iframe = descendableFrameElement();
        when(engine.locateSingle(resolvesTo(scopedContext), any())).thenReturn(result(iframe));

        List<IPendingScope> pending =
                PlaywrightScopeResolver.append(
                        PlaywrightScopeResolver.append(
                                List.of(), new IPendingScope.Element(explicitScopeElement)),
                        new IPendingScope.Frame(FrameDefinition.frame().named("checkout")));

        PlaywrightScopeResolver.resolvePendingScopes(engine, base, pending);

        verify(engine).locateSingle(resolvesTo(scopedContext), any());
    }

    private static IElement frameElement(String url) {
        Locator iframeLocator = mock(Locator.class);
        ElementHandle handle = mock(ElementHandle.class);
        Frame frame = mock(Frame.class);
        when(iframeLocator.elementHandle()).thenReturn(handle);
        when(handle.contentFrame()).thenReturn(frame);
        when(frame.url()).thenReturn(url);
        return new PlaywrightElement(
                iframeLocator,
                ElementRole.UNKNOWN,
                null,
                LocatorScope.page(),
                LocatorConfig.builder().build());
    }

    /** An iframe element usable with {@link PlaywrightScopeResolver#descendIntoFrame}. */
    private static IElement descendableFrameElement() {
        Locator iframeLocator = mock(Locator.class);
        FrameLocator frameLocator = mock(FrameLocator.class);
        Locator documentRoot = mock(Locator.class);
        when(iframeLocator.contentFrame()).thenReturn(frameLocator);
        when(frameLocator.locator("html")).thenReturn(documentRoot);
        return new PlaywrightElement(
                iframeLocator,
                ElementRole.UNKNOWN,
                null,
                LocatorScope.page(),
                LocatorConfig.builder().build());
    }

    /**
     * Matches an {@link ILiveLocatorContext} whose {@link ILiveLocatorContext#resolve()} equals
     * {@code expected} - needed because every frame lookup here is invoked through the live-context
     * overload of {@link ILocatorEngine#locateSingle}, never the plain {@link LocatorContext}
     * overload {@code eq(...)} would match.
     */
    private static ILiveLocatorContext resolvesTo(LocatorContext expected) {
        return argThat(live -> live != null && expected.equals(live.resolve()));
    }

    private static LocatorContext pageContext() {
        return LocatorContext.page(throwingBackend(), LocatorConfig.builder().build());
    }

    private static ILocatorBackend throwingBackend() {
        return (query, scope, config, timeout, limit) -> {
            throw new UnsupportedOperationException("backend must not be invoked directly");
        };
    }

    private static LocatorCandidate candidate(IElement element) {
        return new LocatorCandidate(
                "id-" + System.identityHashCode(element),
                element,
                LocatorStrategyType.CSS,
                1.0,
                1.0,
                0,
                List.of(),
                true,
                true,
                true);
    }

    private static LocatorResult result(IElement element) {
        LocatorDefinition definition = LocatorDefinition.element();
        LocatorDiagnostics diagnostics =
                new LocatorDiagnostics(
                        definition,
                        LocatorResolutionPolicy.BALANCED,
                        LocatorDiagnosticsLevel.BASIC,
                        List.of("Page"),
                        List.of(),
                        List.of(),
                        1,
                        0,
                        0,
                        List.of(),
                        1,
                        0,
                        Optional.empty(),
                        Duration.ZERO,
                        false,
                        Set.of(),
                        Optional.empty(),
                        List.of());
        return new LocatorResult(
                definition,
                element,
                LocatorStrategyType.CSS,
                1.0,
                1.0,
                true,
                List.of(),
                diagnostics);
    }
}
