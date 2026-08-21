package io.webagent4j.browser.playwright;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.microsoft.playwright.Page;
import io.webagent4j.browser.BrowserOptions;
import io.webagent4j.browser.IPage;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * Proves {@code PlaywrightPage}'s own, backend-internal timeout validator - duplicated from {@link
 * IPage}'s private validator rather than shared via a new public utility, since that validator is
 * deliberately not part of {@link IPage}'s public surface (see {@link IPage}'s class-level "Timeout
 * precision" note) - enforces byte-for-byte the same whole-millisecond-precision contract.
 */
class PlaywrightPageTest {

    private IPage page() {
        return new PlaywrightPage(mock(Page.class), BrowserOptions.defaults());
    }

    /**
     * DUR-UNIT: a positive, at-least-1ms timeout carrying a sub-millisecond remainder (1.5ms) must
     * be rejected before ever reaching the underlying Playwright call - the same precision-
     * truncation bug {@link IPage}'s own validator guards against.
     */
    @Test
    void navigateWithTimeoutRejectsFractionalMillisecondValue() {
        assertThatThrownBy(
                        () -> page().navigate("https://example.com/", Duration.ofNanos(1_500_000)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("whole-millisecond");
    }

    @Test
    void navigateWithTimeoutRejectsSubMillisecondValue() {
        assertThatThrownBy(() -> page().navigate("https://example.com/", Duration.ofNanos(500_000)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void waitForConditionRejectsFractionalMillisecondValue() {
        assertThatThrownBy(() -> page().waitForCondition("() => true", Duration.ofNanos(1_500_000)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("whole-millisecond");
    }

    @Test
    void waitForConditionRejectsSubMillisecondValue() {
        assertThatThrownBy(() -> page().waitForCondition("() => true", Duration.ofNanos(500_000)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
