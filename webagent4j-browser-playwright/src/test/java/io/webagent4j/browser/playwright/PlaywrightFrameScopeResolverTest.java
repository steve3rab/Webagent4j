package io.webagent4j.browser.playwright;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatRuntimeException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
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
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.TimeoutError;
import io.webagent4j.browser.FrameDefinition;
import io.webagent4j.common.LocatorException;
import io.webagent4j.dom.IElement;
import io.webagent4j.locator.AmbiguousLocatorException;
import io.webagent4j.locator.ILiveLocatorContext;
import io.webagent4j.locator.ILocatorBackend;
import io.webagent4j.locator.ILocatorEngine;
import io.webagent4j.locator.LocatorBackendQuery;
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
import java.util.Map;
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
    void frameUrlInspectionUsesCurrentHandlesWithoutStartingANestedWait() {
        ILocatorEngine engine = mock(ILocatorEngine.class);
        LocatorContext context = pageContext();
        Locator iframeLocator = mock(Locator.class);
        ElementHandle handle = mock(ElementHandle.class);
        Frame frame = mock(Frame.class);

        when(iframeLocator.elementHandles()).thenReturn(List.of(handle));
        when(handle.contentFrame()).thenReturn(frame);
        when(frame.url()).thenReturn("https://example.com/checkout");

        IElement iframe =
                new PlaywrightElement(
                        iframeLocator,
                        ElementRole.UNKNOWN,
                        null,
                        LocatorScope.page(),
                        LocatorConfig.builder().build());

        when(engine.locateAll(eq(context), any())).thenReturn(List.of(candidate(iframe)));

        FrameDefinition definition =
                FrameDefinition.frame().withUrl(TextMatch.exact("https://example.com/checkout"));

        PlaywrightScopeResolver.resolveFrameElement(
                engine, ILiveLocatorContext.fixed(context), definition, Duration.ofMillis(100));

        verify(iframeLocator).elementHandles();
    }

    @Test
    void expiredFrameUrlBudgetUsesOnlyTheExistingOneNanosecondFinalProbe() {
        ILocatorEngine engine = mock(ILocatorEngine.class);
        LocatorContext context = pageContext();
        Locator iframeLocator = mock(Locator.class);
        ElementHandle handle = mock(ElementHandle.class);
        Frame frame = mock(Frame.class);

        when(iframeLocator.elementHandles()).thenReturn(List.of(handle));
        when(handle.contentFrame()).thenReturn(frame);
        when(frame.url()).thenReturn("https://example.com/checkout");

        IElement iframe =
                new PlaywrightElement(
                        iframeLocator,
                        ElementRole.UNKNOWN,
                        null,
                        LocatorScope.page(),
                        LocatorConfig.builder().build());

        when(engine.locateAll(eq(context), any())).thenReturn(List.of(candidate(iframe)));

        FrameDefinition definition =
                FrameDefinition.frame().withUrl(TextMatch.exact("https://example.com/checkout"));

        PlaywrightScopeResolver.resolveFrameElement(
                engine, ILiveLocatorContext.fixed(context), definition, Duration.ZERO);

        verify(iframeLocator).elementHandles();
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

        when(iframeLocator.elementHandles()).thenReturn(List.of(handle));
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

    /**
     * The {@code <iframe>} element itself vanishing between {@code locateAll} discovery and this
     * inspection is a normal detachment race, not a backend failure.
     */
    @Test
    void aCandidateElementThatVanishesWithATimeoutErrorIsExcludedFromThisPollNotPropagated() {
        ILocatorEngine engine = mock(ILocatorEngine.class);
        LocatorContext context = pageContext();
        Locator iframeLocator = mock(Locator.class);

        when(iframeLocator.elementHandles())
                .thenThrow(new TimeoutError("Timeout exceeded while resolving element handle"));
        when(iframeLocator.count()).thenReturn(0);

        IElement vanished =
                new PlaywrightElement(
                        iframeLocator,
                        ElementRole.UNKNOWN,
                        null,
                        LocatorScope.page(),
                        LocatorConfig.builder().build());

        when(engine.locateAll(eq(context), any())).thenReturn(List.of(candidate(vanished)));

        FrameDefinition definition =
                FrameDefinition.frame().withUrl(TextMatch.exact("https://example.com/checkout"));

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
    void anExplicitFrameDetachmentDuringUrlInspectionIsExcludedFromThisPoll() {
        ILocatorEngine engine = mock(ILocatorEngine.class);
        LocatorContext context = pageContext();
        Locator iframeLocator = mock(Locator.class);

        when(iframeLocator.elementHandles())
                .thenThrow(
                        new PlaywrightException(
                                "Error {\n"
                                        + "  message='Frame was detached\n"
                                        + "  name='Error\n"
                                        + "}"));

        IElement vanished =
                new PlaywrightElement(
                        iframeLocator,
                        ElementRole.UNKNOWN,
                        null,
                        LocatorScope.page(),
                        LocatorConfig.builder().build());

        when(engine.locateAll(eq(context), any())).thenReturn(List.of(candidate(vanished)));

        FrameDefinition definition =
                FrameDefinition.frame().withUrl(TextMatch.exact("https://example.com/checkout"));

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
    void aUrlInspectionTimeoutForAStillPresentCandidatePropagatesUnchanged() {
        ILocatorEngine engine = mock(ILocatorEngine.class);
        LocatorContext context = pageContext();
        Locator iframeLocator = mock(Locator.class);
        TimeoutError timeout = new TimeoutError("URL inspection exceeded its bound");

        when(iframeLocator.elementHandles()).thenThrow(timeout);
        when(iframeLocator.count()).thenReturn(1);

        IElement iframe =
                new PlaywrightElement(
                        iframeLocator,
                        ElementRole.UNKNOWN,
                        null,
                        LocatorScope.page(),
                        LocatorConfig.builder().build());

        when(engine.locateAll(eq(context), any())).thenReturn(List.of(candidate(iframe)));

        FrameDefinition definition =
                FrameDefinition.frame().withUrl(TextMatch.exact("https://example.com/checkout"));

        assertThatRuntimeException()
                .isThrownBy(
                        () ->
                                PlaywrightScopeResolver.resolveFrameElement(
                                        engine,
                                        ILiveLocatorContext.fixed(context),
                                        definition,
                                        SHORT_TIMEOUT))
                .isSameAs(timeout);
    }

    @Test
    void frameUrlInspectionDoesNotCreateAnIndependentTimeout() {
        ILocatorEngine engine = mock(ILocatorEngine.class);
        LocatorContext context = pageContext();
        Locator iframeLocator = mock(Locator.class);
        ElementHandle handle = mock(ElementHandle.class);
        Frame frame = mock(Frame.class);

        when(iframeLocator.elementHandles()).thenReturn(List.of(handle));
        when(handle.contentFrame()).thenReturn(frame);
        when(frame.url()).thenReturn("https://example.com/checkout");

        IElement iframe =
                new PlaywrightElement(
                        iframeLocator,
                        ElementRole.UNKNOWN,
                        null,
                        LocatorScope.page(),
                        LocatorConfig.builder().build());

        when(engine.locateAll(eq(context), any())).thenReturn(List.of(candidate(iframe)));

        FrameDefinition definition =
                FrameDefinition.frame().withUrl(TextMatch.exact("https://example.com/checkout"));

        PlaywrightScopeResolver.resolveFrameElement(
                engine, ILiveLocatorContext.fixed(context), definition, Duration.ofSeconds(4));

        verify(iframeLocator).elementHandles();
    }

    @Test
    void aFailedUrlInspectionRecheckPreservesTheOriginalTimeout() {
        ILocatorEngine engine = mock(ILocatorEngine.class);
        LocatorContext context = pageContext();
        Locator iframeLocator = mock(Locator.class);

        TimeoutError timeout = new TimeoutError("URL inspection exceeded its bound");
        RuntimeException recheckFailure = new IllegalStateException("browser disconnected");

        when(iframeLocator.elementHandles()).thenThrow(timeout);
        when(iframeLocator.count()).thenThrow(recheckFailure);

        IElement iframe =
                new PlaywrightElement(
                        iframeLocator,
                        ElementRole.UNKNOWN,
                        null,
                        LocatorScope.page(),
                        LocatorConfig.builder().build());

        when(engine.locateAll(eq(context), any())).thenReturn(List.of(candidate(iframe)));

        FrameDefinition definition =
                FrameDefinition.frame().withUrl(TextMatch.exact("https://example.com/checkout"));

        assertThatRuntimeException()
                .isThrownBy(
                        () ->
                                PlaywrightScopeResolver.resolveFrameElement(
                                        engine,
                                        ILiveLocatorContext.fixed(context),
                                        definition,
                                        SHORT_TIMEOUT))
                .isSameAs(timeout)
                .satisfies(
                        failure ->
                                assertThat(failure.getSuppressed())
                                        .containsExactly(recheckFailure));
    }

    /**
     * A genuine backend or runtime failure encountered while inspecting a URL candidate must
     * propagate unchanged.
     */
    @Test
    void aGenuineBackendFailureDuringUrlInspectionPropagatesUnchangedRatherThanBecomingNotFound() {
        ILocatorEngine engine = mock(ILocatorEngine.class);
        LocatorContext context = pageContext();
        Locator iframeLocator = mock(Locator.class);
        RuntimeException backendFailure = new IllegalStateException("browser disconnected");

        when(iframeLocator.elementHandles()).thenThrow(backendFailure);

        IElement iframe =
                new PlaywrightElement(
                        iframeLocator,
                        ElementRole.UNKNOWN,
                        null,
                        LocatorScope.page(),
                        LocatorConfig.builder().build());

        when(engine.locateAll(eq(context), any())).thenReturn(List.of(candidate(iframe)));

        FrameDefinition definition =
                FrameDefinition.frame().withUrl(TextMatch.exact("https://example.com/checkout"));

        assertThatRuntimeException()
                .isThrownBy(
                        () ->
                                PlaywrightScopeResolver.resolveFrameElement(
                                        engine,
                                        ILiveLocatorContext.fixed(context),
                                        definition,
                                        Duration.ofMillis(250)))
                .isSameAs(backendFailure);
    }

    @Test
    void theCallerSuppliedTimeoutBoundsTheImmediateFrameQuery() {
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

        Duration internalTimeout = captor.getValue().timeout().orElseThrow();

        assertThat(internalTimeout).isPositive().isLessThanOrEqualTo(Duration.ofMillis(250));

        assertThat(captor.getValue().css()).contains("iframe[name=\"checkout\" i]");

        verify(engine).locateAll(eq(context), any());
    }

    @Test
    void resolveFrameScopeUsesOnlyTheRemainingPrerequisiteBudget() {
        ILocatorEngine engine = mock(ILocatorEngine.class);
        LocatorContext context = pageContext();
        IElement iframe = descendableFrameElement();

        ArgumentCaptor<LocatorDefinition> captor = ArgumentCaptor.forClass(LocatorDefinition.class);

        when(engine.locateAll(eq(context), captor.capture()))
                .thenReturn(List.of(candidate(iframe)));

        LocatorContext resolved =
                PlaywrightScopeResolver.resolveFrameScope(
                        engine, context, FrameDefinition.frame().named("checkout"));

        Duration internalTimeout = captor.getValue().timeout().orElseThrow();

        assertThat(internalTimeout)
                .isPositive()
                .isLessThanOrEqualTo(context.config().resolutionBudget().timeout());

        assertThat(resolved.scope().type()).isEqualTo(LocatorScopeType.FRAME);
        assertThat(resolved.scope().root()).isEmpty();
        assertThat(resolved.scope().path()).containsExactly("Frame[name=\"checkout\"]");

        verify(engine).locateAll(eq(context), any());
    }

    @Test
    void descendingIntoAFrameCapturesTheContentFrameFromTheSameHandleUsedToResolveTheIframe() {
        ILocatorEngine engine = mock(ILocatorEngine.class);
        LocatorContext context = pageContext();
        Locator iframeLocator = mock(Locator.class);
        ElementHandle handle = mock(ElementHandle.class);
        Frame frame = mock(Frame.class);
        Locator documentRoot = mock(Locator.class);
        when(iframeLocator.elementHandles()).thenReturn(List.of(handle));
        when(handle.contentFrame()).thenReturn(frame);
        when(frame.locator("html")).thenReturn(documentRoot);
        IElement iframe =
                new PlaywrightElement(
                        iframeLocator,
                        ElementRole.UNKNOWN,
                        null,
                        LocatorScope.page(),
                        LocatorConfig.builder().build());
        when(engine.locateAll(eq(context), any())).thenReturn(List.of(candidate(iframe)));

        PlaywrightScopeResolver.resolveFrameScope(
                engine, context, FrameDefinition.frame().named("checkout"));

        verify(iframeLocator).elementHandles();
        verify(handle).contentFrame();
        verify(frame).locator("html");
        // Never through a separately, independently re-resolving FrameLocator - the whole point is
        // that the physical iframe node and its content frame are captured together, atomically.
        verify(iframeLocator, never()).contentFrame();
    }

    @Test
    void aDetachedContentFrameDuringDescentIsATypedRetryableNotFoundNotASilentSuccess() {
        ILocatorEngine engine = mock(ILocatorEngine.class);
        LocatorContext context = pageContext();
        Locator iframeLocator = mock(Locator.class);
        ElementHandle handle = mock(ElementHandle.class);
        Frame frame = mock(Frame.class);
        when(iframeLocator.elementHandles()).thenReturn(List.of(handle));
        when(handle.contentFrame()).thenReturn(frame);
        when(frame.isDetached()).thenReturn(true);
        IElement iframe =
                new PlaywrightElement(
                        iframeLocator,
                        ElementRole.UNKNOWN,
                        null,
                        LocatorScope.page(),
                        LocatorConfig.builder().build());
        when(engine.locateAll(eq(context), any())).thenReturn(List.of(candidate(iframe)));

        assertThatRuntimeException()
                .isThrownBy(
                        () ->
                                PlaywrightScopeResolver.resolveFrameScope(
                                        engine, context, FrameDefinition.frame().named("checkout")))
                .isInstanceOf(LocatorNotFoundException.class);
    }

    @Test
    void aMissingContentFrameDuringDescentIsATypedRetryableNotFound() {
        ILocatorEngine engine = mock(ILocatorEngine.class);
        LocatorContext context = pageContext();
        Locator iframeLocator = mock(Locator.class);
        ElementHandle handle = mock(ElementHandle.class);
        when(iframeLocator.elementHandles()).thenReturn(List.of(handle));
        when(handle.contentFrame()).thenReturn(null);
        IElement iframe =
                new PlaywrightElement(
                        iframeLocator,
                        ElementRole.UNKNOWN,
                        null,
                        LocatorScope.page(),
                        LocatorConfig.builder().build());
        when(engine.locateAll(eq(context), any())).thenReturn(List.of(candidate(iframe)));

        assertThatRuntimeException()
                .isThrownBy(
                        () ->
                                PlaywrightScopeResolver.resolveFrameScope(
                                        engine, context, FrameDefinition.frame().named("checkout")))
                .isInstanceOf(LocatorNotFoundException.class);
    }

    @Test
    void anIframeElementThatVanishesJustBeforeContentFrameCaptureIsATypedRetryableNotFound() {
        ILocatorEngine engine = mock(ILocatorEngine.class);
        LocatorContext context = pageContext();
        Locator iframeLocator = mock(Locator.class);
        when(iframeLocator.elementHandles()).thenReturn(List.of());
        IElement iframe =
                new PlaywrightElement(
                        iframeLocator,
                        ElementRole.UNKNOWN,
                        null,
                        LocatorScope.page(),
                        LocatorConfig.builder().build());
        when(engine.locateAll(eq(context), any())).thenReturn(List.of(candidate(iframe)));

        assertThatRuntimeException()
                .isThrownBy(
                        () ->
                                PlaywrightScopeResolver.resolveFrameScope(
                                        engine, context, FrameDefinition.frame().named("checkout")))
                .isInstanceOf(LocatorNotFoundException.class);
    }

    @Test
    void aDocumentTransitionRaceWhileCapturingTheContentFrameIsRetryableNotFatal() {
        ILocatorEngine engine = mock(ILocatorEngine.class);
        LocatorContext context = pageContext();
        Locator iframeLocator = mock(Locator.class);
        PlaywrightException contextDestroyed =
                new PlaywrightException(
                        "elementHandle.contentFrame: Execution context was destroyed, most likely"
                                + " because of a navigation");
        when(iframeLocator.elementHandles()).thenThrow(contextDestroyed);
        IElement iframe =
                new PlaywrightElement(
                        iframeLocator,
                        ElementRole.UNKNOWN,
                        null,
                        LocatorScope.page(),
                        LocatorConfig.builder().build());
        when(engine.locateAll(eq(context), any())).thenReturn(List.of(candidate(iframe)));

        // A genuine, engine-reported document/execution-context transition mid-capture is never a
        // fatal failure: it is exactly the transient condition frame descent inherently races, and
        // must be absorbed by the caller's own bounded wait loop instead of aborting resolution.
        assertThatRuntimeException()
                .isThrownBy(
                        () ->
                                PlaywrightScopeResolver.resolveFrameScope(
                                        engine, context, FrameDefinition.frame().named("checkout")))
                .isInstanceOf(LocatorNotFoundException.class);
    }

    @Test
    void descendingIntoAFrameSettlesTheDocumentBeforeReturningItsContext() {
        ILocatorEngine engine = mock(ILocatorEngine.class);
        LocatorContext context = pageContext();
        Locator iframeLocator = mock(Locator.class);
        ElementHandle handle = mock(ElementHandle.class);
        Frame frame = mock(Frame.class);
        Locator documentRoot = mock(Locator.class);
        when(iframeLocator.elementHandles()).thenReturn(List.of(handle));
        when(handle.contentFrame()).thenReturn(frame);
        when(frame.locator("html")).thenReturn(documentRoot);
        when(documentRoot.count()).thenReturn(1);
        IElement iframe =
                new PlaywrightElement(
                        iframeLocator,
                        ElementRole.UNKNOWN,
                        null,
                        LocatorScope.page(),
                        LocatorConfig.builder().build());
        when(engine.locateAll(eq(context), any())).thenReturn(List.of(candidate(iframe)));

        LocatorContext resolved =
                PlaywrightScopeResolver.resolveFrameScope(
                        engine, context, FrameDefinition.frame().named("checkout"));

        // The settle probe is a plain, current-DOM count() - never a nested locator wait, and
        // never in addition to a second, independent capture of the content frame or document
        // root: exactly one round trip against the exact same handle/frame/document already
        // captured above.
        verify(documentRoot).count();
        assertThat(resolved).isNotNull();
    }

    @Test
    void aDocumentTransitionRaceWhileSettlingTheContentDocumentIsRetryableNotFatal() {
        ILocatorEngine engine = mock(ILocatorEngine.class);
        LocatorContext context = pageContext();
        Locator iframeLocator = mock(Locator.class);
        ElementHandle handle = mock(ElementHandle.class);
        Frame frame = mock(Frame.class);
        Locator documentRoot = mock(Locator.class);
        when(iframeLocator.elementHandles()).thenReturn(List.of(handle));
        when(handle.contentFrame()).thenReturn(frame);
        when(frame.locator("html")).thenReturn(documentRoot);
        PlaywrightException contextDestroyed =
                new PlaywrightException(
                        "locator.count: Execution context was destroyed, most likely because of a"
                                + " navigation");
        when(documentRoot.count()).thenThrow(contextDestroyed);
        IElement iframe =
                new PlaywrightElement(
                        iframeLocator,
                        ElementRole.UNKNOWN,
                        null,
                        LocatorScope.page(),
                        LocatorConfig.builder().build());
        when(engine.locateAll(eq(context), any())).thenReturn(List.of(candidate(iframe)));

        // At least one non-Chromium engine is documented to tear down and recreate a
        // still-attached iframe document's own execution context - and, with it, this process's
        // per-document identity bridge - around the frame's initial attachment. This proves that
        // race, observed on the settle probe itself rather than during content-frame capture, is
        // absorbed by the caller's own bounded wait loop instead of aborting resolution: never a
        // permanent, non-retried failure for what is, physically, the exact same, never-detached
        // document.
        assertThatRuntimeException()
                .isThrownBy(
                        () ->
                                PlaywrightScopeResolver.resolveFrameScope(
                                        engine, context, FrameDefinition.frame().named("checkout")))
                .isInstanceOf(LocatorNotFoundException.class);
    }

    @Test
    void aFrameUnavailableSignalWhileSettlingTheContentDocumentIsRetryableNotFatal() {
        ILocatorEngine engine = mock(ILocatorEngine.class);
        LocatorContext context = pageContext();
        Locator iframeLocator = mock(Locator.class);
        ElementHandle handle = mock(ElementHandle.class);
        Frame frame = mock(Frame.class);
        Locator documentRoot = mock(Locator.class);
        when(iframeLocator.elementHandles()).thenReturn(List.of(handle));
        when(handle.contentFrame()).thenReturn(frame);
        when(frame.locator("html")).thenReturn(documentRoot);
        when(documentRoot.count()).thenThrow(new PlaywrightException("Frame was detached"));
        IElement iframe =
                new PlaywrightElement(
                        iframeLocator,
                        ElementRole.UNKNOWN,
                        null,
                        LocatorScope.page(),
                        LocatorConfig.builder().build());
        when(engine.locateAll(eq(context), any())).thenReturn(List.of(candidate(iframe)));

        assertThatRuntimeException()
                .isThrownBy(
                        () ->
                                PlaywrightScopeResolver.resolveFrameScope(
                                        engine, context, FrameDefinition.frame().named("checkout")))
                .isInstanceOf(LocatorNotFoundException.class);
    }

    @Test
    void aGenuineBackendFailureWhileSettlingTheContentDocumentPropagatesUnchanged() {
        ILocatorEngine engine = mock(ILocatorEngine.class);
        LocatorContext context = pageContext();
        Locator iframeLocator = mock(Locator.class);
        ElementHandle handle = mock(ElementHandle.class);
        Frame frame = mock(Frame.class);
        Locator documentRoot = mock(Locator.class);
        when(iframeLocator.elementHandles()).thenReturn(List.of(handle));
        when(handle.contentFrame()).thenReturn(frame);
        when(frame.locator("html")).thenReturn(documentRoot);
        PlaywrightException opaqueBackendFailure =
                new PlaywrightException("Target page, context or browser has been closed");
        when(documentRoot.count()).thenThrow(opaqueBackendFailure);
        IElement iframe =
                new PlaywrightElement(
                        iframeLocator,
                        ElementRole.UNKNOWN,
                        null,
                        LocatorScope.page(),
                        LocatorConfig.builder().build());
        when(engine.locateAll(eq(context), any())).thenReturn(List.of(candidate(iframe)));

        // An opaque, unclassified backend failure while settling must never be silently
        // reclassified as a retryable not-found - only the two narrow, stable disappearance
        // signals above ever are.
        assertThatRuntimeException()
                .isThrownBy(
                        () ->
                                PlaywrightScopeResolver.resolveFrameScope(
                                        engine, context, FrameDefinition.frame().named("checkout")))
                .isSameAs(opaqueBackendFailure);
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

    /**
     * FX-REALM-007: a two-level nested frame descent (outer, then inner) must bind the backend a
     * caller finally searches with to the <em>inner</em> child frame's own document root, never the
     * outer's - a physical node inside the inner document must never be discoverable, nor its
     * identity ever captured, against the wrong document. {@link #descendableFrameElement()} gives
     * the outer and inner iframe candidates each their own distinct mock {@link Frame} and content
     * {@link Locator}, so this is directly observable: the inner document root's own {@code
     * locator(...)} stub is the only one ever asked for the button, and the outer's is never
     * touched at all.
     */
    @Test
    void nestedFrameDescentBindsCandidateSearchToTheInnerFramesOwnDocumentRootNotTheOuters() {
        ILocatorEngine engine = mock(ILocatorEngine.class);
        LocatorContext base = pageContext();

        Locator outerIframeLocator = mock(Locator.class);
        ElementHandle outerHandle = mock(ElementHandle.class);
        Frame outerChildFrame = mock(Frame.class);
        Locator outerDocumentRoot = mock(Locator.class);
        when(outerIframeLocator.elementHandles()).thenReturn(List.of(outerHandle));
        when(outerHandle.contentFrame()).thenReturn(outerChildFrame);
        when(outerChildFrame.locator("html")).thenReturn(outerDocumentRoot);
        IElement outerIframe =
                new PlaywrightElement(
                        outerIframeLocator,
                        ElementRole.UNKNOWN,
                        null,
                        LocatorScope.page(),
                        LocatorConfig.builder().build());

        Locator innerIframeLocator = mock(Locator.class);
        ElementHandle innerHandle = mock(ElementHandle.class);
        Frame innerChildFrame = mock(Frame.class);
        Locator innerDocumentRoot = mock(Locator.class);
        when(innerIframeLocator.elementHandles()).thenReturn(List.of(innerHandle));
        when(innerHandle.contentFrame()).thenReturn(innerChildFrame);
        when(innerChildFrame.locator("html")).thenReturn(innerDocumentRoot);
        IElement innerIframe =
                new PlaywrightElement(
                        innerIframeLocator,
                        ElementRole.UNKNOWN,
                        null,
                        LocatorScope.page(),
                        LocatorConfig.builder().build());

        when(engine.locateAll(any(LocatorContext.class), any()))
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

        Locator innerButtonMatch = mock(Locator.class);
        Locator innerButtonItem = mock(Locator.class);
        ElementHandle innerButtonHandle = mock(ElementHandle.class);
        when(innerDocumentRoot.locator("button")).thenReturn(innerButtonMatch);
        when(innerButtonMatch.count()).thenReturn(1);
        when(innerButtonMatch.nth(0)).thenReturn(innerButtonItem);
        when(innerButtonItem.elementHandles()).thenReturn(List.of(innerButtonHandle));
        when(innerButtonHandle.evaluate(anyString(), any()))
                .thenReturn(Map.of("identity", "webagent4j-inner-1", "domOrder", 0));

        var searchResult =
                ((ILocatorBackend) resolved.backend())
                        .find(
                                new LocatorBackendQuery(
                                        LocatorStrategyType.CSS,
                                        Optional.empty(),
                                        Optional.empty(),
                                        Optional.empty(),
                                        Optional.of("button")),
                                resolved.scope(),
                                resolved.config(),
                                Duration.ofSeconds(1),
                                10);

        assertThat(searchResult.candidates()).hasSize(1);
        assertThat(searchResult.candidates().getFirst().identity()).isEqualTo("webagent4j-inner-1");
        verify(innerDocumentRoot).locator("button");
        verify(outerDocumentRoot, never()).locator(anyString());
    }

    @Test
    void anExplicitElementScopeDeclaredBeforeAFrameNarrowsWhereTheFrameIsSearchedFor() {
        ILocatorEngine engine = mock(ILocatorEngine.class);
        LocatorContext base = pageContext();

        Locator explicitLocator = mock(Locator.class);

        /*
         * LocatorContext.within(...) asks the element for its accessible name.
         * PlaywrightElement now uses evaluateAll() for current-DOM inspection,
         * therefore the mock must return the {count,value} inspection envelope.
         */
        when(explicitLocator.evaluateAll(anyString()))
                .thenReturn(Map.of("count", 1, "value", "Explicit scope"));

        IElement explicitScopeElement =
                new PlaywrightElement(
                        explicitLocator,
                        ElementRole.REGION,
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

        when(iframeLocator.elementHandles()).thenReturn(List.of(handle));

        when(handle.contentFrame()).thenReturn(frame);

        when(frame.url()).thenReturn(url);

        return new PlaywrightElement(
                iframeLocator,
                ElementRole.UNKNOWN,
                null,
                LocatorScope.page(),
                LocatorConfig.builder().build());
    }

    /**
     * An iframe element usable with {@link PlaywrightScopeResolver#descendIntoFrame}: its content
     * frame is captured atomically from the same {@link ElementHandle} used to resolve it, never
     * through a separately re-resolving {@link FrameLocator}.
     */
    private static IElement descendableFrameElement() {
        Locator iframeLocator = mock(Locator.class);

        ElementHandle handle = mock(ElementHandle.class);

        Frame frame = mock(Frame.class);

        Locator documentRoot = mock(Locator.class);

        when(iframeLocator.elementHandles()).thenReturn(List.of(handle));

        when(handle.contentFrame()).thenReturn(frame);

        when(frame.locator("html")).thenReturn(documentRoot);

        return new PlaywrightElement(
                iframeLocator,
                ElementRole.UNKNOWN,
                null,
                LocatorScope.page(),
                LocatorConfig.builder().build());
    }

    /**
     * An iframe element usable both as a url-matching candidate and, if the caller descends into
     * it, with {@link PlaywrightScopeResolver#descendIntoFrame}.
     */
    private static IElement descendableFrameElementWithUrl(String url) {

        Locator iframeLocator = mock(Locator.class);

        ElementHandle handle = mock(ElementHandle.class);

        Frame frame = mock(Frame.class);

        Locator documentRoot = mock(Locator.class);

        when(iframeLocator.elementHandles()).thenReturn(List.of(handle));

        when(handle.contentFrame()).thenReturn(frame);

        when(frame.url()).thenReturn(url);

        when(frame.locator("html")).thenReturn(documentRoot);

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
