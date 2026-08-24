package io.webagent4j.browser.playwright;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.TimeoutError;
import io.webagent4j.dom.ElementState;
import io.webagent4j.locator.AmbiguousLocatorException;
import io.webagent4j.locator.LocatorConfig;
import io.webagent4j.locator.LocatorScope;
import io.webagent4j.locator.api.ElementRole;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Regression coverage for current-DOM element inspection.
 *
 * <p>Read-only inspection must not start a nested Playwright wait whose tiny timeout can turn a
 * normal locator deadline into a backend failure. The inspection observes the locator's current
 * cardinality through {@link Locator#evaluateAll(String)}: zero is proven absence, one is
 * inspected, and more than one fails closed as ambiguity.
 */
class PlaywrightElementBudgetTest {

    @Test
    void zeroCurrentMatchesAreReportedAsDetached() {
        Locator locator = mock(Locator.class);
        when(locator.evaluateAll(anyString())).thenReturn(Map.of("count", 0));

        ElementState state = element(locator, 1_000.0).state();

        assertThat(state.detached()).isTrue();
        assertThat(state.present()).isFalse();
    }

    @Test
    void aMinusculeDiscoveryTimeoutCannotCreateANestedInspectionTimeout() {
        Locator locator = mock(Locator.class);
        when(locator.evaluateAll(anyString()))
                .thenReturn(
                        Map.of("count", 1, "value", presentStateValue()),
                        Map.of("count", 1, "value", "button"),
                        Map.of("count", 1, "value", "Confirm"),
                        Map.of("count", 1, "value", Map.of("id", "confirm")));

        PlaywrightElement element = element(locator, 0.000001);

        assertThatCode(element::state).doesNotThrowAnyException();
        assertThat(element.role()).isEqualTo(ElementRole.BUTTON);
        assertThat(element.accessibleName()).isEqualTo("Confirm");
        assertThat(element.attributes()).containsEntry("id", "confirm");

        verify(locator, never()).evaluate(anyString(), any(), any(Locator.EvaluateOptions.class));
    }

    @Test
    void repeatedInspectionsRemainCurrentDomOperationsRegardlessOfHowManyPrecededThem() {
        Locator locator = mock(Locator.class);
        when(locator.evaluateAll(anyString()))
                .thenReturn(Map.of("count", 1, "value", presentStateValue()));

        PlaywrightElement element = element(locator, 0.000001);

        for (int i = 0; i < 5; i++) {
            assertThat(element.state().detached()).isFalse();
        }

        verify(locator, never()).evaluate(anyString(), any(), any(Locator.EvaluateOptions.class));
    }

    @Test
    void multipleCurrentMatchesFailClosedAsAmbiguous() {
        Locator locator = mock(Locator.class);
        when(locator.evaluateAll(anyString())).thenReturn(Map.of("count", 2));

        PlaywrightElement element = element(locator, 1.0);

        assertThatThrownBy(element::state).isInstanceOf(AmbiguousLocatorException.class);
    }

    @Test
    void aCurrentDomTimeoutIsStillARealBackendFailureAndPropagatesUnchanged() {
        Locator locator = mock(Locator.class);
        TimeoutError timeout = new TimeoutError("current DOM evaluation timed out");
        when(locator.evaluateAll(anyString())).thenThrow(timeout);

        PlaywrightElement element = element(locator, 1.0);

        assertThatThrownBy(element::state).isSameAs(timeout);
    }

    @Test
    void aGenuineBackendFailureDuringCurrentDomInspectionPropagatesUnchanged() {
        Locator locator = mock(Locator.class);
        PlaywrightException failure = new PlaywrightException("browser disconnected");
        when(locator.evaluateAll(anyString())).thenThrow(failure);

        PlaywrightElement element = element(locator, 1.0);

        assertThatThrownBy(element::state).isSameAs(failure);
    }

    @Test
    void aCanonicalMissingFrameFailureProducesDetachedState() {
        Locator locator = mock(Locator.class);
        when(locator.evaluateAll(anyString())).thenThrow(frameMissingForSelectorFailure());

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
        when(locator.evaluateAll(anyString())).thenThrow(failure);

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

    private static PlaywrightException frameMissingForSelectorFailure() {
        return new PlaywrightException(
                "Error {\n"
                        + "  message='Failed to find frame for selector \"html >> iframe\"\n"
                        + "  name='Error\n"
                        + "}");
    }
}
