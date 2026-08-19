package io.webagent4j.browser.playwright;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatRuntimeException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
import io.webagent4j.locator.LocatorNotFoundException;
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
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Unit-level proof of {@link PlaywrightScopeResolver}'s frame-specific resolution contracts: the
 * same 0/1/N -> not-found/success/ambiguous classification element resolution already uses, with
 * every criterion - id, name, title, and url - filtering candidates before that classification is
 * ever applied; a document-boundary chain that starts fresh rather than nesting under the parent
 * path; declaration order preserved across mixed element/frame scopes; and the one-shot-versus-
 * real-timeout split that keeps a prerequisite frame hop from silently multiplying an outer wait's
 * budget.
 */
class PlaywrightFrameScopeResolverTest {

    private static final Duration SHORT_TIMEOUT = Duration.ofMillis(60);

    @Test
    void singleMatchWithNoUrlCriterionResolvesDirectly() {
        ILocatorEngine engine = mock(ILocatorEngine.class);
        LocatorContext context = pageContext();
        IElement iframe = frameElement("https://example.com/checkout");
        when(engine.locateAll(eq(context), any())).thenReturn(List.of(candidate(iframe)));

        LocatorResult resolved =
                PlaywrightScopeResolver.resolveFrameElement(
                        engine,
                        ILiveLocatorContext.fixed(context),
                        FrameDefinition.frame().named("checkout"),
                        Duration.ofMillis(250));

        assertThat(resolved.element()).isSameAs(iframe);
    }

    @Test
    void zeroMatchesTimesOutAsATypedNotFound() {
        ILocatorEngine engine = mock(ILocatorEngine.class);
        LocatorContext context = pageContext();
        when(engine.locateAll(eq(context), any())).thenReturn(List.of());

        assertThatRuntimeException()
                .isThrownBy(
                        () ->
                                PlaywrightScopeResolver.resolveFrameElement(
                                        engine,
                                        ILiveLocatorContext.fixed(context),
                                        FrameDefinition.frame().named("checkout"),
                                        SHORT_TIMEOUT))
                .isInstanceOf(LocatorNotFoundException.class);
    }

    @Test
    void multipleMatchesFailsClosedAsAmbiguousOnTheVeryFirstPoll() {
        ILocatorEngine engine = mock(ILocatorEngine.class);
        LocatorContext context = pageContext();
        IElement first = frameElement("https://example.com/a");
        IElement second = frameElement("https://example.com/b");
        when(engine.locateAll(eq(context), any()))
                .thenReturn(List.of(candidate(first), candidate(second)));

        assertThatRuntimeException()
                .isThrownBy(
                        () ->
                                PlaywrightScopeResolver.resolveFrameElement(
                                        engine,
                                        ILiveLocatorContext.fixed(context),
                                        FrameDefinition.frame().named("payment"),
                                        Duration.ofMillis(250)))
                .isInstanceOf(AmbiguousLocatorException.class);
    }

    @Test
    void aGenuineBackendFailureIsNeverReclassifiedAsNotFoundOrAmbiguous() {
        ILocatorEngine engine = mock(ILocatorEngine.class);
        LocatorContext context = pageContext();
        RuntimeException backendFailure = new IllegalStateException("browser disconnected");
        when(engine.locateAll(eq(context), any())).thenThrow(backendFailure);

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
    void aUrlCriterionSelectsTheSingleMatchingFrame() {
        ILocatorEngine engine = mock(ILocatorEngine.class);
        LocatorContext context = pageContext();
        IElement iframe = frameElement("https://example.com/checkout");
        when(engine.locateAll(eq(context), any())).thenReturn(List.of(candidate(iframe)));
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
        when(engine.locateAll(eq(context), any())).thenReturn(List.of(candidate(iframe)));
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
                                        SHORT_TIMEOUT))
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
        when(engine.locateAll(eq(context), any())).thenReturn(List.of(candidate(iframe)));
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
                                        SHORT_TIMEOUT))
                .isInstanceOf(LocatorNotFoundException.class);
    }

    @Test
    void theCallerSuppliedTimeoutGovernsTheOuterWaitWhileTheQueryStaysOneShot() {
        ILocatorEngine engine = mock(ILocatorEngine.class);
        LocatorContext context = pageContext();
        IElement iframe = frameElement("https://example.com/checkout");
        ArgumentCaptor<LocatorDefinition> captor = ArgumentCaptor.forClass(LocatorDefinition.class);
        when(engine.locateAll(eq(context), captor.capture()))
                .thenReturn(List.of(candidate(iframe)));
        FrameDefinition definition =
                FrameDefinition.frame().named("checkout").withTimeout(Duration.ofSeconds(99));

        PlaywrightScopeResolver.resolveFrameElement(
                engine, ILiveLocatorContext.fixed(context), definition, Duration.ofMillis(250));

        assertThat(captor.getValue().timeout()).contains(Duration.ofNanos(1));
        assertThat(captor.getValue().css()).contains("iframe[name=\"checkout\" i]");
    }

    @Test
    void resolveFrameScopeBoundsThePrerequisiteHopToAOneShotTimeout() {
        ILocatorEngine engine = mock(ILocatorEngine.class);
        LocatorContext context = pageContext();
        IElement iframe = descendableFrameElement();
        ArgumentCaptor<LocatorDefinition> captor = ArgumentCaptor.forClass(LocatorDefinition.class);
        when(engine.locateAll(eq(context), captor.capture()))
                .thenReturn(List.of(candidate(iframe)));

        LocatorContext resolved =
                PlaywrightScopeResolver.resolveFrameScope(
                        engine, context, FrameDefinition.frame().named("checkout"));

        assertThat(captor.getValue().timeout()).contains(Duration.ofNanos(1));
        assertThat(resolved.scope().type()).isEqualTo(LocatorScopeType.FRAME);
        assertThat(resolved.scope().root()).isEmpty();
        assertThat(resolved.scope().path()).containsExactly("Frame[name=\"checkout\"]");
    }

    @Test
    void resolveFrameScopeProbesExactlyOnceWhenNothingMatchesRatherThanRetrying() {
        ILocatorEngine engine = mock(ILocatorEngine.class);
        LocatorContext context = pageContext();
        when(engine.locateAll(eq(context), any())).thenReturn(List.of());

        assertThatRuntimeException()
                .isThrownBy(
                        () ->
                                PlaywrightScopeResolver.resolveFrameScope(
                                        engine, context, FrameDefinition.frame().named("checkout")))
                .isInstanceOf(LocatorNotFoundException.class);
        verify(engine, times(1)).locateAll(eq(context), any());
    }

    @Test
    void resolveTerminalFrameElementUsesTheRealCallerSuppliedTimeoutAndGenuinelyRetries() {
        ILocatorEngine engine = mock(ILocatorEngine.class);
        LocatorContext context = pageContext();
        IElement iframe = frameElement("https://example.com/checkout");
        when(engine.locateAll(eq(context), any()))
                .thenReturn(List.of())
                .thenReturn(List.of())
                .thenReturn(List.of(candidate(iframe)));

        LocatorResult resolved =
                PlaywrightScopeResolver.resolveTerminalFrameElement(
                        engine,
                        context,
                        List.of(),
                        FrameDefinition.frame().named("checkout"),
                        Duration.ofSeconds(2));

        assertThat(resolved.element()).isSameAs(iframe);
        verify(engine, times(3)).locateAll(eq(context), any());
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
        verify(engine, never()).locateAll(any(LocatorContext.class), any());
    }

    @Test
    void
            nestedFramesResolveInDeclarationOrderEachAgainstThePriorFramesOwnDocumentNotTheOriginalBase() {
        ILocatorEngine engine = mock(ILocatorEngine.class);
        LocatorContext base = pageContext();
        IElement outerIframe = descendableFrameElement();
        IElement innerIframe = descendableFrameElement();
        ArgumentCaptor<LocatorContext> contextCaptor =
                ArgumentCaptor.forClass(LocatorContext.class);
        when(engine.locateAll(contextCaptor.capture(), any()))
                .thenAnswer(
                        invocation -> {
                            LocatorContext resolvedContext = invocation.getArgument(0);
                            return base.equals(resolvedContext)
                                    ? List.of(candidate(outerIframe))
                                    : List.of(candidate(innerIframe));
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
        assertThat(contextCaptor.getAllValues()).anyMatch(candidate -> !candidate.equals(base));
    }

    @Test
    void nestedFrameResolvesWithAUrlCriterionAtTheInnerHop() {
        ILocatorEngine engine = mock(ILocatorEngine.class);
        LocatorContext base = pageContext();
        IElement outerIframe = descendableFrameElement();
        IElement innerMatch = descendableFrameElementWithUrl("https://inner.example.com/target");
        IElement innerDecoy = descendableFrameElementWithUrl("https://inner.example.com/decoy");
        when(engine.locateAll(any(LocatorContext.class), any()))
                .thenAnswer(
                        invocation -> {
                            LocatorContext resolvedContext = invocation.getArgument(0);
                            return base.equals(resolvedContext)
                                    ? List.of(candidate(outerIframe))
                                    : List.of(candidate(innerMatch), candidate(innerDecoy));
                        });

        List<IPendingScope> pending =
                PlaywrightScopeResolver.append(
                        PlaywrightScopeResolver.append(
                                List.of(),
                                new IPendingScope.Frame(FrameDefinition.frame().named("outer"))),
                        new IPendingScope.Frame(
                                FrameDefinition.frame()
                                        .withUrl(
                                                TextMatch.exact(
                                                        "https://inner.example.com/target"))));

        LocatorContext resolved =
                PlaywrightScopeResolver.resolvePendingScopes(engine, base, pending);

        assertThat(resolved.scope().type()).isEqualTo(LocatorScopeType.FRAME);
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
        when(engine.locateAll(eq(scopedContext), any())).thenReturn(List.of(candidate(iframe)));

        List<IPendingScope> pending =
                PlaywrightScopeResolver.append(
                        PlaywrightScopeResolver.append(
                                List.of(), new IPendingScope.Element(explicitScopeElement)),
                        new IPendingScope.Frame(FrameDefinition.frame().named("checkout")));

        PlaywrightScopeResolver.resolvePendingScopes(engine, base, pending);

        verify(engine).locateAll(eq(scopedContext), any());
    }

    // --- URL criterion disambiguation (Section 1) ---

    @Test
    void twoFramesSharingANameAreDisambiguatedByDifferentUrls() {
        ILocatorEngine engine = mock(ILocatorEngine.class);
        LocatorContext context = pageContext();
        IElement wanted = frameElement("https://example.com/checkout-a");
        IElement other = frameElement("https://example.com/checkout-b");
        when(engine.locateAll(eq(context), any()))
                .thenReturn(List.of(candidate(wanted), candidate(other)));
        FrameDefinition definition =
                FrameDefinition.frame()
                        .named("checkout")
                        .withUrl(TextMatch.exact("https://example.com/checkout-a"));

        LocatorResult resolved =
                PlaywrightScopeResolver.resolveFrameElement(
                        engine,
                        ILiveLocatorContext.fixed(context),
                        definition,
                        Duration.ofMillis(250));

        assertThat(resolved.element()).isSameAs(wanted);
    }

    @Test
    void twoFramesSharingBothNameAndUrlAreStillAmbiguous() {
        ILocatorEngine engine = mock(ILocatorEngine.class);
        LocatorContext context = pageContext();
        IElement first = frameElement("https://example.com/checkout");
        IElement second = frameElement("https://example.com/checkout");
        when(engine.locateAll(eq(context), any()))
                .thenReturn(List.of(candidate(first), candidate(second)));
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
                .isInstanceOf(AmbiguousLocatorException.class);
    }

    @Test
    void aUrlCriterionAloneSelectsOneFrameAmongSeveralUnnamedCandidates() {
        ILocatorEngine engine = mock(ILocatorEngine.class);
        LocatorContext context = pageContext();
        IElement decoyBefore = frameElement("https://example.com/account");
        IElement wanted = frameElement("https://example.com/billing");
        IElement decoyAfter = frameElement("https://example.com/shipping");
        when(engine.locateAll(eq(context), any()))
                .thenReturn(
                        List.of(candidate(decoyBefore), candidate(wanted), candidate(decoyAfter)));
        FrameDefinition definition =
                FrameDefinition.frame().withUrl(TextMatch.exact("https://example.com/billing"));

        LocatorResult resolved =
                PlaywrightScopeResolver.resolveFrameElement(
                        engine,
                        ILiveLocatorContext.fixed(context),
                        definition,
                        Duration.ofMillis(250));

        assertThat(resolved.element()).isSameAs(wanted);
    }

    @Test
    void aNonexistentUrlAmongSeveralFramesIsATypedNotFound() {
        ILocatorEngine engine = mock(ILocatorEngine.class);
        LocatorContext context = pageContext();
        IElement first = frameElement("https://example.com/account");
        IElement second = frameElement("https://example.com/shipping");
        when(engine.locateAll(eq(context), any()))
                .thenReturn(List.of(candidate(first), candidate(second)));
        FrameDefinition definition =
                FrameDefinition.frame().withUrl(TextMatch.exact("https://example.com/nonexistent"));

        assertThatRuntimeException()
                .isThrownBy(
                        () ->
                                PlaywrightScopeResolver.resolveFrameElement(
                                        engine,
                                        ILiveLocatorContext.fixed(context),
                                        definition,
                                        SHORT_TIMEOUT))
                .isInstanceOf(LocatorNotFoundException.class);
    }

    @Test
    void urlCriterionSupportsContains() {
        assertUrlMatches(
                "https://example.com/store/checkout/step-1",
                new TextMatch(TextMatchType.CONTAINS, "checkout"));
    }

    @Test
    void urlCriterionSupportsStartsWith() {
        assertUrlMatches(
                "https://example.com/checkout/step-1",
                new TextMatch(TextMatchType.STARTS_WITH, "https://example.com/checkout"));
    }

    @Test
    void urlCriterionSupportsEndsWith() {
        assertUrlMatches(
                "https://example.com/checkout/step-1",
                new TextMatch(TextMatchType.ENDS_WITH, "step-1"));
    }

    @Test
    void urlCriterionSupportsRegex() {
        assertUrlMatches(
                "https://example.com/checkout/step-42",
                new TextMatch(TextMatchType.REGEX, "https://example\\.com/checkout/step-\\d+"));
    }

    @Test
    void urlCriterionSupportsCaseInsensitiveExact() {
        assertUrlMatches(
                "https://EXAMPLE.com/Checkout",
                new TextMatch(
                        TextMatchType.CASE_INSENSITIVE_EXACT, "https://example.com/checkout"));
    }

    @Test
    void urlCriterionSupportsExact() {
        assertUrlMatches(
                "https://example.com/checkout", TextMatch.exact("https://example.com/checkout"));
    }

    private static void assertUrlMatches(String actualUrl, TextMatch criterion) {
        ILocatorEngine engine = mock(ILocatorEngine.class);
        LocatorContext context = pageContext();
        IElement iframe = frameElement(actualUrl);
        when(engine.locateAll(eq(context), any())).thenReturn(List.of(candidate(iframe)));
        FrameDefinition definition = FrameDefinition.frame().withUrl(criterion);

        LocatorResult resolved =
                PlaywrightScopeResolver.resolveFrameElement(
                        engine,
                        ILiveLocatorContext.fixed(context),
                        definition,
                        Duration.ofMillis(250));

        assertThat(resolved.element()).isSameAs(iframe);
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
     * An iframe element usable both as a url-matching candidate (via {@link Frame#url()}) and, if
     * the caller descends into it, with {@link PlaywrightScopeResolver#descendIntoFrame}.
     */
    private static IElement descendableFrameElementWithUrl(String url) {
        Locator iframeLocator = mock(Locator.class);
        ElementHandle handle = mock(ElementHandle.class);
        Frame frame = mock(Frame.class);
        FrameLocator frameLocator = mock(FrameLocator.class);
        Locator documentRoot = mock(Locator.class);
        when(iframeLocator.elementHandle()).thenReturn(handle);
        when(handle.contentFrame()).thenReturn(frame);
        when(frame.url()).thenReturn(url);
        when(iframeLocator.contentFrame()).thenReturn(frameLocator);
        when(frameLocator.locator("html")).thenReturn(documentRoot);
        return new PlaywrightElement(
                iframeLocator,
                ElementRole.UNKNOWN,
                null,
                LocatorScope.page(),
                LocatorConfig.builder().build());
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
}
