package io.webagent4j.browser.playwright;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.TimeoutError;
import io.webagent4j.dom.ElementState;
import io.webagent4j.locator.LocatorConfig;
import io.webagent4j.locator.LocatorScope;
import io.webagent4j.locator.api.ElementRole;
import io.webagent4j.wait.WaitBudget;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** Regression coverage for the one shared deadline used by current element inspections. */
class PlaywrightElementBudgetTest {

    @Test
    void anAlreadyExpiredBudgetTimesOutBeforeCheckingWhetherTheElementIsPresent() {
        Locator locator = mock(Locator.class);
        AtomicLong clock = new AtomicLong();
        WaitBudget budget = WaitBudget.start(Duration.ofNanos(1), clock::get);
        clock.set(1);

        assertThatThrownBy(() -> element(locator, budget).state())
                .isInstanceOf(TimeoutError.class)
                .hasMessageContaining("caller timeout");
        verifyNoInteractions(locator);
    }

    @Test
    void expirationAfterPresenceWasConfirmedTimesOutBeforeStateEvaluation() {
        Locator locator = mock(Locator.class);
        AtomicLong clock = new AtomicLong();
        WaitBudget budget = WaitBudget.start(Duration.ofNanos(1), clock::get);
        when(locator.count())
                .thenAnswer(
                        invocation -> {
                            clock.set(1);
                            return 1;
                        });

        assertThatThrownBy(() -> element(locator, budget).state())
                .isInstanceOf(TimeoutError.class)
                .hasMessageContaining("caller timeout");
        verify(locator, never()).evaluate(any(), any(), any(Locator.EvaluateOptions.class));
    }

    @Test
    void aCurrentZeroCountIsStillAProvenDetachedElement() {
        Locator locator = mock(Locator.class);
        when(locator.count()).thenReturn(0);

        ElementState state =
                element(locator, WaitBudget.start(Duration.ofSeconds(1), () -> 0L)).state();

        assertThat(state.detached()).isTrue();
    }

    @Test
    void aTimedOutCountFollowedByAZeroCountIsStillAProvenDetachedElement() {
        Locator locator = mock(Locator.class);
        when(locator.count()).thenThrow(new TimeoutError("count timed out")).thenReturn(0);

        ElementState state =
                element(locator, WaitBudget.start(Duration.ofSeconds(1), () -> 0L)).state();

        assertThat(state.detached()).isTrue();
    }

    @Test
    void expiredMetadataInspectionCannotReturnEmptyAttributes() {
        assertExpiredInspectionFails(elementWithExpiredBudget()::attributes);
    }

    @Test
    void expiredNameInspectionCannotReturnAnEmptyName() {
        assertExpiredInspectionFails(elementWithExpiredBudget()::accessibleName);
    }

    @Test
    void expiredRoleInspectionCannotReturnUnknown() {
        assertExpiredInspectionFails(elementWithExpiredBudget()::role);
    }

    private static void assertExpiredInspectionFails(Runnable inspection) {
        assertThatThrownBy(inspection::run)
                .isInstanceOf(TimeoutError.class)
                .hasMessageContaining("caller timeout");
    }

    private static PlaywrightElement elementWithExpiredBudget() {
        AtomicLong clock = new AtomicLong();
        WaitBudget budget = WaitBudget.start(Duration.ZERO, clock::get);
        return element(mock(Locator.class), budget);
    }

    private static PlaywrightElement element(Locator locator, WaitBudget budget) {
        return new PlaywrightElement(
                locator,
                ElementRole.UNKNOWN,
                null,
                LocatorScope.page(),
                LocatorConfig.defaults(),
                budget);
    }
}
