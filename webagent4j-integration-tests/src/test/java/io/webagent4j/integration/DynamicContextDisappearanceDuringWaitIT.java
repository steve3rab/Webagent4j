package io.webagent4j.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import io.webagent4j.browser.InteractionContext;
import io.webagent4j.locator.LocatorNotFoundException;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * Proves a structured semantic scope that disappears while an active wait is still polling ends the
 * wait as a transient "not found" - exactly like an absent target already does - never by reusing
 * whichever concrete DOM node the scope resolved to on an earlier attempt. A {@code stableFor(...)}
 * wait started at t=0, while the "Shipping" region and its "Continue" button both exist, is still
 * actively polling when the region is removed 150ms later and nothing ever replaces it: resolution
 * must end in {@link LocatorNotFoundException} once the bounded timeout is reached, not resolve
 * successfully against a stale reference to the removed node. Independent proof comes from the
 * fixture's own server-side click counter, which must stay at zero.
 */
class DynamicContextDisappearanceDuringWaitIT {

    @Test
    void aContextThatDisappearsWhileActivelyWaitingNeverResolvesAgainstAStaleNode()
            throws Exception {
        try (var support = Phase4TestSupport.start();
                var page = support.open("/actions/context-dynamic-disappears")) {
            assertThatExceptionOfType(LocatorNotFoundException.class)
                    .isThrownBy(
                            () ->
                                    page.find(
                                                    InteractionContext.context()
                                                            .containingText("Shipping"))
                                            .button()
                                            .named("Continue")
                                            .stableFor(Duration.ofMillis(300))
                                            .timeout(Duration.ofMillis(800))
                                            .single());

            assertThat(support.clickCount("shipping-solo")).isZero();
        }
    }
}
