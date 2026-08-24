package io.webagent4j.browser.playwright;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.microsoft.playwright.ElementHandle;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.TimeoutError;
import io.webagent4j.dom.ElementState;
import io.webagent4j.locator.AmbiguousLocatorException;
import io.webagent4j.locator.LocatorConfig;
import io.webagent4j.locator.LocatorScope;
import io.webagent4j.locator.api.ElementRole;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Regression coverage for current-DOM element inspection.
 *
 * <p>Read-only inspection resolves current handles without starting a nested locator wait. Zero
 * current matches are proven absence, one current match is inspected directly through its physical
 * handle, and more than one current match fails closed as ambiguity. In particular, the adapter
 * must never use {@link Locator#evaluateAll(String)} for a locator that can contain an isolated
 * custom selector engine.
 */
class PlaywrightElementBudgetTest {

    @Test
    void zeroCurrentMatchesAreReportedAsDetached() {
        Locator locator = mock(Locator.class);
        when(locator.elementHandles()).thenReturn(List.of());

        ElementState state = element(locator, 1_000.0).state();

        assertThat(state.detached()).isTrue();
        assertThat(state.present()).isFalse();
    }

    @Test
    void aMinusculeDiscoveryTimeoutCannotCreateANestedInspectionTimeout() {
        Locator locator = mock(Locator.class);
        ElementHandle handle = mock(ElementHandle.class);
        when(locator.elementHandles()).thenReturn(List.of(handle));
        when(handle.evaluate(anyString(), isNull()))
                .thenReturn(presentStateValue(), "button", "Confirm", Map.of("id", "confirm"));

        PlaywrightElement element = element(locator, 0.000001);

        assertThatCode(element::state).doesNotThrowAnyException();
        assertThat(element.role()).isEqualTo(ElementRole.BUTTON);
        assertThat(element.accessibleName()).isEqualTo("Confirm");
        assertThat(element.attributes()).containsEntry("id", "confirm");

        verify(locator, never()).evaluateAll(anyString());
        verify(locator, never()).evaluate(anyString(), any(), any(Locator.EvaluateOptions.class));
    }

    @Test
    void repeatedInspectionsRemainCurrentDomOperationsRegardlessOfHowManyPrecededThem() {
        Locator locator = mock(Locator.class);
        ElementHandle handle = mock(ElementHandle.class);
        when(locator.elementHandles()).thenReturn(List.of(handle));
        when(handle.evaluate(anyString(), isNull())).thenReturn(presentStateValue());

        PlaywrightElement element = element(locator, 0.000001);

        for (int i = 0; i < 5; i++) {
            assertThat(element.state().detached()).isFalse();
        }

        verify(locator, never()).evaluateAll(anyString());
        verify(locator, never()).evaluate(anyString(), any(), any(Locator.EvaluateOptions.class));
    }

    @Test
    void multipleCurrentMatchesFailClosedAsAmbiguous() {
        Locator locator = mock(Locator.class);
        ElementHandle first = mock(ElementHandle.class);
        ElementHandle second = mock(ElementHandle.class);
        when(locator.elementHandles()).thenReturn(List.of(first, second));

        PlaywrightElement element = element(locator, 1.0);

        assertThatThrownBy(element::state).isInstanceOf(AmbiguousLocatorException.class);
        verify(first).dispose();
        verify(second).dispose();
    }

    @Test
    void anUnexpectedCurrentDomTimeoutIsStillARealBackendFailureAndPropagatesUnchanged() {
        Locator locator = mock(Locator.class);
        TimeoutError timeout = new TimeoutError("current DOM handle query timed out");
        when(locator.elementHandles()).thenThrow(timeout);

        PlaywrightElement element = element(locator, 1.0);

        assertThatThrownBy(element::state).isSameAs(timeout);
    }

    @Test
    void aGenuineBackendFailureDuringCurrentDomInspectionPropagatesUnchanged() {
        Locator locator = mock(Locator.class);
        ElementHandle handle = mock(ElementHandle.class);
        PlaywrightException failure = new PlaywrightException("browser disconnected");
        when(locator.elementHandles()).thenReturn(List.of(handle));
        when(handle.evaluate(anyString(), isNull())).thenThrow(failure);

        PlaywrightElement element = element(locator, 1.0);

        assertThatThrownBy(element::state).isSameAs(failure);
        verify(handle).dispose();
    }

    @Test
    void aDifferentDocumentAdoptionRaceBecomesDetachedOnlyWhenARecheckProvesAbsence() {
        Locator locator = mock(Locator.class);
        PlaywrightException adoption = differentDocumentAdoptionFailure();
        when(locator.elementHandles()).thenThrow(adoption);
        when(locator.count()).thenReturn(0);

        ElementState state = element(locator, 1.0).state();

        assertThat(state.detached()).isTrue();
        assertThat(state.present()).isFalse();
    }

    @Test
    void aDifferentDocumentAdoptionRaceForAStillPresentElementPropagatesUnchanged() {
        Locator locator = mock(Locator.class);
        PlaywrightException adoption = differentDocumentAdoptionFailure();
        when(locator.elementHandles()).thenThrow(adoption);
        when(locator.count()).thenReturn(1);

        assertThatThrownBy(() -> element(locator, 1.0).state()).isSameAs(adoption);
    }

    @Test
    void aDifferentDocumentAdoptionRaceWithAnOpaqueRecheckFailurePreservesTheOriginalFailure() {
        Locator locator = mock(Locator.class);
        PlaywrightException adoption = differentDocumentAdoptionFailure();
        RuntimeException recheckFailure = new IllegalStateException("browser disconnected");
        when(locator.elementHandles()).thenThrow(adoption);
        when(locator.count()).thenThrow(recheckFailure);

        assertThatThrownBy(() -> element(locator, 1.0).state())
                .isSameAs(adoption)
                .satisfies(
                        failure ->
                                assertThat(failure.getSuppressed())
                                        .containsExactly(recheckFailure));
    }

    @Test
    void aMissingExecutionContextRaceBecomesDetachedOnlyWhenARecheckProvesAbsence() {
        Locator locator = mock(Locator.class);
        PlaywrightException contextLoss = describeNodeContextMissingFailure();
        when(locator.elementHandles()).thenThrow(contextLoss);
        when(locator.count()).thenReturn(0);

        ElementState state = element(locator, 1.0).state();

        assertThat(state.detached()).isTrue();
        assertThat(state.present()).isFalse();
    }

    @Test
    void aMissingExecutionContextRaceForAStillPresentElementPropagatesUnchanged() {
        Locator locator = mock(Locator.class);
        PlaywrightException contextLoss = describeNodeContextMissingFailure();
        when(locator.elementHandles()).thenThrow(contextLoss);
        when(locator.count()).thenReturn(1);

        assertThatThrownBy(() -> element(locator, 1.0).state()).isSameAs(contextLoss);
    }

    @Test
    void aMissingExecutionContextRaceWithOpaqueRecheckFailurePreservesOriginalFailure() {
        Locator locator = mock(Locator.class);
        PlaywrightException contextLoss = describeNodeContextMissingFailure();
        RuntimeException recheckFailure = new IllegalStateException("browser disconnected");
        when(locator.elementHandles()).thenThrow(contextLoss);
        when(locator.count()).thenThrow(recheckFailure);

        assertThatThrownBy(() -> element(locator, 1.0).state())
                .isSameAs(contextLoss)
                .satisfies(
                        failure ->
                                assertThat(failure.getSuppressed())
                                        .containsExactly(recheckFailure));
    }

    @Test
    void anOpaqueFailureThatOnlyMentionsTheMissingContextSignatureStillPropagates() {
        Locator locator = mock(Locator.class);
        PlaywrightException failure =
                new PlaywrightException(
                        "browser disconnected after Protocol error (DOM.describeNode): "
                                + "Cannot find context with specified id");
        when(locator.elementHandles()).thenThrow(failure);

        assertThatThrownBy(() -> element(locator, 1.0).state()).isSameAs(failure);
        verify(locator, never()).count();
    }

    @Test
    void aCanonicalMissingFrameFailureProducesDetachedState() {
        Locator locator = mock(Locator.class);
        when(locator.elementHandles()).thenThrow(frameMissingForSelectorFailure());

        ElementState state = element(locator, 1.0).state();

        assertThat(state.detached()).isTrue();
        assertThat(state.present()).isFalse();
    }

    @Test
    void anOpaqueFailureThatOnlyMentionsAMissingFrameStillPropagates() {
        Locator locator = mock(Locator.class);
        PlaywrightException failure =
                new PlaywrightException(
                        "browser disconnected after Failed to find frame for selector x");
        when(locator.elementHandles()).thenThrow(failure);

        assertThatThrownBy(() -> element(locator, 1.0).state()).isSameAs(failure);
    }

    private static Map<String, Object> presentStateValue() {
        return Map.ofEntries(
                Map.entry("present", true),
                Map.entry("visible", true),
                Map.entry("enabled", true),
                Map.entry("editable", false),
                Map.entry("readOnly", false),
                Map.entry("checked", false),
                Map.entry("selected", false),
                Map.entry("focused", false),
                Map.entry("inViewport", true),
                Map.entry("clickable", true),
                Map.entry("covered", false));
    }

    private static PlaywrightElement element(Locator locator, double inspectionTimeoutMillis) {
        return new PlaywrightElement(
                locator,
                ElementRole.UNKNOWN,
                null,
                LocatorScope.page(),
                LocatorConfig.defaults(),
                inspectionTimeoutMillis);
    }

    private static PlaywrightException differentDocumentAdoptionFailure() {
        return new PlaywrightException(
                "Error {\n"
                        + "  message='Unable to adopt element handle from a different document\n"
                        + "  name='Error\n"
                        + "}");
    }

    private static PlaywrightException describeNodeContextMissingFailure() {
        return new PlaywrightException(
                "Error {\n"
                        + "  message='Protocol error (DOM.describeNode): "
                        + "Cannot find context with specified id\n"
                        + "  name='Error\n"
                        + "}");
    }

    private static PlaywrightException frameMissingForSelectorFailure() {
        return new PlaywrightException(
                "Error {\n"
                        + "  message='Failed to find frame for selector \"html >> iframe\"\n"
                        + "  name='Error\n"
                        + "}");
    }
}
