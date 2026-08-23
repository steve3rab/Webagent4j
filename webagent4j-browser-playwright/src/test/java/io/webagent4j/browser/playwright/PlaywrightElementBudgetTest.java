package io.webagent4j.browser.playwright;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.TimeoutError;
import io.webagent4j.dom.ElementState;
import io.webagent4j.locator.LocatorConfig;
import io.webagent4j.locator.LocatorScope;
import io.webagent4j.locator.api.ElementRole;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Regression coverage for the fixed, non-draining, per-call inspection timeout used by current
 * element inspections. Unlike a shared, monotonically-draining deadline, this value is a plain
 * millis number handed to each individual Playwright call; it never accumulates elapsed wall-clock
 * time across separate operations on the same element, so a discovery-time timeout - however small
 * - can never later cause {@code state()}, {@code role()}, {@code accessibleName()}, or {@code
 * attributes()} to fail merely because time has passed since the element was discovered.
 */
class PlaywrightElementBudgetTest {

    @Test
    void aCurrentZeroCountIsStillAProvenDetachedElement() {
        Locator locator = mock(Locator.class);
        when(locator.count()).thenReturn(0);

        ElementState state = element(locator, 1_000.0).state();

        assertThat(state.detached()).isTrue();
    }

    @Test
    void aTimedOutCountFollowedByAZeroCountIsStillAProvenDetachedElement() {
        Locator locator = mock(Locator.class);
        when(locator.count()).thenThrow(new TimeoutError("count timed out")).thenReturn(0);

        ElementState state = element(locator, 1_000.0).state();

        assertThat(state.detached()).isTrue();
    }

    /**
     * A minuscule discovery-time inspection timeout must not later cause any of these operations to
     * fail merely because it is small: it is a fixed per-call Playwright timeout, not a shared
     * deadline that drains with elapsed wall-clock time.
     */
    @Test
    void aMinusculeInspectionTimeoutNeverFailsStateRoleNameOrAttributes() {
        Locator locator = mock(Locator.class);
        when(locator.count()).thenReturn(1);
        when(locator.evaluate(any(), any(), any(Locator.EvaluateOptions.class)))
                .thenReturn(Map.of());

        PlaywrightElement element = element(locator, 1.0);

        assertThatCode(element::state).doesNotThrowAnyException();
        assertThatCode(element::role).doesNotThrowAnyException();
        assertThatCode(element::accessibleName).doesNotThrowAnyException();
        assertThatCode(element::attributes).doesNotThrowAnyException();
    }

    /**
     * The same fixed timeout is handed to every one of several successive inspections of the same
     * element: it never decreases, because it is not tracked against any clock or shared budget.
     */
    @Test
    void repeatedInspectionsOnTheSameElementAllSucceedRegardlessOfHowManyPrecededThem() {
        Locator locator = mock(Locator.class);
        when(locator.count()).thenReturn(1);
        when(locator.evaluate(any(), any(), any(Locator.EvaluateOptions.class)))
                .thenReturn(presentStateValue());

        PlaywrightElement element = element(locator, 1.0);

        for (int i = 0; i < 5; i++) {
            assertThat(element.state().detached()).isFalse();
        }
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
}
