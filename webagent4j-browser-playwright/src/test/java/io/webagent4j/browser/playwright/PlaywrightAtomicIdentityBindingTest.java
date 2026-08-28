package io.webagent4j.browser.playwright;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.microsoft.playwright.ElementHandle;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.PlaywrightException;
import io.webagent4j.browser.BrowserOptions;
import io.webagent4j.dom.IElement;
import io.webagent4j.locator.LocatorConfig;
import io.webagent4j.locator.LocatorScope;
import io.webagent4j.locator.api.ElementRole;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Proves the fix for the residual TOCTOU between an action-policy identity check and the native
 * backend call it is supposed to gate: {@link PlaywrightElement#verifiedForExecution()} and {@link
 * PlaywrightLocatorBackend#resolveVerifiedHandleOrNull} verify identity and capture the physical
 * {@link ElementHandle} in one operation, so a caller's subsequent native call always acts on
 * precisely the handle that was just checked - never on a second, independently re-resolved {@link
 * Locator} lookup that could silently land on a different physical node satisfying the same
 * locator.
 *
 * <p>The scenario every test here defends against: candidate A is selected and its identity
 * captured; the live DOM then replaces A with B, a different physical node that still satisfies the
 * exact same locator; a naive "check identity, then separately re-resolve and act" sequence would
 * silently act on B while believing it was still acting on A. None of these tests ever accept that
 * substitution.
 */
class PlaywrightAtomicIdentityBindingTest {

    private static final String CAPTURED_IDENTITY = "webagent4j-A";

    @Test
    void verifiedForExecutionReturnsTheExactHandleWhenIdentityIsReproven() {
        Locator locator = mock(Locator.class);
        ElementHandle handle = mock(ElementHandle.class);
        when(locator.elementHandles()).thenReturn(List.of(handle));
        when(handle.evaluate(anyString(), any()))
                .thenReturn(Map.of("identity", CAPTURED_IDENTITY, "domOrder", 0));

        PlaywrightElement element = elementWithCapturedIdentity(locator, CAPTURED_IDENTITY);
        Optional<IElement> verified = element.verifiedForExecution();

        assertThat(verified).isPresent();
        PlaywrightElement verifiedElement = (PlaywrightElement) verified.orElseThrow();
        assertThat(verifiedElement.verifiedHandle()).contains(handle);
        verify(handle, never()).dispose();
    }

    @Test
    void
            aPhysicallyReplacedElementIsNeverSilentlyAcceptedEvenThoughTheLocatorStillMatchesSomething() {
        // Candidate A was selected and "webagent4j-A" captured as its identity. Between that
        // capture and this atomic re-verification, the DOM replaced A with B - a different
        // physical node the exact same locator still resolves to. B's own identity token differs
        // from A's, and B must never be accepted as a stand-in for A.
        Locator locator = mock(Locator.class);
        ElementHandle replacementHandle = mock(ElementHandle.class);
        when(locator.elementHandles()).thenReturn(List.of(replacementHandle));
        when(replacementHandle.evaluate(anyString(), any()))
                .thenReturn(Map.of("identity", "webagent4j-B", "domOrder", 0));

        PlaywrightElement element = elementWithCapturedIdentity(locator, CAPTURED_IDENTITY);

        assertThat(element.verifiedForExecution()).isEmpty();
        // The replacement's handle must not leak just because it was rejected.
        verify(replacementHandle).dispose();
    }

    @Test
    void aDetachedElementFailsClosedRatherThanBeingTreatedAsStillPresent() {
        Locator locator = mock(Locator.class);
        when(locator.elementHandles()).thenReturn(List.of());

        PlaywrightElement element = elementWithCapturedIdentity(locator, CAPTURED_IDENTITY);

        assertThat(element.verifiedForExecution()).isEmpty();
    }

    @Test
    void multipleCurrentlyMatchingPhysicalNodesFailClosedInsteadOfPickingOne() {
        // Mirrors "multiple matching iframes/elements": the locator that used to resolve to
        // exactly one physical node now resolves to more than one. Silently picking either one
        // would be exactly the kind of unproven substitution this method exists to prevent.
        Locator locator = mock(Locator.class);
        ElementHandle first = mock(ElementHandle.class);
        ElementHandle second = mock(ElementHandle.class);
        when(locator.elementHandles()).thenReturn(List.of(first, second));

        PlaywrightElement element = elementWithCapturedIdentity(locator, CAPTURED_IDENTITY);

        assertThat(element.verifiedForExecution()).isEmpty();
        verify(first).dispose();
        verify(second).dispose();
    }

    @Test
    void anInspectionExceptionFailsClosedAndNeverLeaksTheHandle() {
        Locator locator = mock(Locator.class);
        ElementHandle handle = mock(ElementHandle.class);
        when(locator.elementHandles()).thenReturn(List.of(handle));
        when(handle.evaluate(anyString(), any()))
                .thenThrow(new IllegalStateException("browser disconnected"));

        PlaywrightElement element = elementWithCapturedIdentity(locator, CAPTURED_IDENTITY);

        assertThat(element.verifiedForExecution()).isEmpty();
        verify(handle).dispose();
    }

    @Test
    void aDocumentTransitionRaceFailsClosedEvenThoughTheCandidateIsStillPresent() {
        // The still-present-but-racing-a-transition condition is retryable at the caller's own
        // bounded wait loop, but it is never silently treated as a proven match here: this
        // atomic check only ever returns present or absent, never "probably fine."
        Locator locator = mock(Locator.class);
        PlaywrightException raceFailure =
                new PlaywrightException(
                        "Error {\n"
                                + "  message='Unable to adopt element handle from a different"
                                + " document\n"
                                + "  name='Error\n"
                                + "}");
        when(locator.elementHandles()).thenThrow(raceFailure);
        when(locator.count()).thenReturn(1);

        PlaywrightElement element = elementWithCapturedIdentity(locator, CAPTURED_IDENTITY);

        assertThat(element.verifiedForExecution()).isEmpty();
    }

    @Test
    void noCapturedIdentityFallsBackToTheOrdinaryPathWithoutAnyExtraNativeCall() {
        // An element that never captured an identity (an ungoverned resolution) has nothing to
        // atomically defend, so this must stay a pure no-cost pass-through: no elementHandles()
        // call, no identity script evaluation - exactly this backend's pre-existing behavior.
        Locator locator = mock(Locator.class);
        PlaywrightElement element =
                new PlaywrightElement(
                        locator,
                        ElementRole.BUTTON,
                        null,
                        LocatorScope.page(),
                        LocatorConfig.defaults());

        Optional<IElement> verified = element.verifiedForExecution();

        assertThat(verified).contains(element);
        verify(locator, never()).elementHandles();
    }

    @Test
    void resolveVerifiedHandleOrNullReturnsNullAndDisposesWhenTheFreshIdentityDoesNotMatch() {
        Locator item = mock(Locator.class);
        ElementHandle handle = mock(ElementHandle.class);
        when(item.elementHandles()).thenReturn(List.of(handle));
        when(handle.evaluate(anyString(), any()))
                .thenReturn(Map.of("identity", "webagent4j-B", "domOrder", 0));

        PlaywrightLocatorBackend.VerifiedHandle verified =
                PlaywrightLocatorBackend.resolveVerifiedHandleOrNull(item, CAPTURED_IDENTITY);

        assertThat(verified).isNull();
        verify(handle).dispose();
    }

    @Test
    void resolveVerifiedHandleOrNullReturnsTheOpenHandleWhenIdentityMatches() {
        Locator item = mock(Locator.class);
        ElementHandle handle = mock(ElementHandle.class);
        when(item.elementHandles()).thenReturn(List.of(handle));
        when(handle.evaluate(anyString(), any()))
                .thenReturn(Map.of("identity", CAPTURED_IDENTITY, "domOrder", 0));

        PlaywrightLocatorBackend.VerifiedHandle verified =
                PlaywrightLocatorBackend.resolveVerifiedHandleOrNull(item, CAPTURED_IDENTITY);

        assertThat(verified).isNotNull();
        assertThat(verified.handle()).isSameAs(handle);
        assertThat(verified.identity()).isEqualTo(CAPTURED_IDENTITY);
        verify(handle, never()).dispose();
    }

    @Test
    void clickConsumesTheExactVerifiedHandleInsteadOfReResolvingTheLocator() {
        Locator locator = mock(Locator.class);
        ElementHandle handle = mock(ElementHandle.class);
        when(locator.elementHandles()).thenReturn(List.of(handle));
        when(handle.evaluate(anyString(), any()))
                .thenReturn(Map.of("identity", CAPTURED_IDENTITY, "domOrder", 0));

        PlaywrightElement element = elementWithCapturedIdentity(locator, CAPTURED_IDENTITY);
        IElement verified = element.verifiedForExecution().orElseThrow();
        PlaywrightActionBackend backend =
                new PlaywrightActionBackend(mock(Page.class), BrowserOptions.defaults());

        backend.click(verified);

        verify(handle, times(1)).click();
        verify(locator, never()).click();
    }

    @Test
    void clickFallsBackToTheLocatorWhenNoIdentityWasCaptured() {
        Locator locator = mock(Locator.class);
        PlaywrightElement element =
                new PlaywrightElement(
                        locator,
                        ElementRole.BUTTON,
                        null,
                        LocatorScope.page(),
                        LocatorConfig.defaults());
        PlaywrightActionBackend backend =
                new PlaywrightActionBackend(mock(Page.class), BrowserOptions.defaults());

        backend.click(element);

        verify(locator, times(1)).click();
        verify(locator, never()).elementHandles();
    }

    private static PlaywrightElement elementWithCapturedIdentity(
            Locator locator, String capturedIdentity) {
        return new PlaywrightElement(
                locator,
                ElementRole.BUTTON,
                null,
                LocatorScope.page(),
                LocatorConfig.defaults(),
                1_000.0,
                null,
                capturedIdentity);
    }
}
