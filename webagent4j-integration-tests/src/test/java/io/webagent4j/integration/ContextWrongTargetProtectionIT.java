package io.webagent4j.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.webagent4j.browser.InteractionContext;
import io.webagent4j.locator.AmbiguousLocatorException;
import org.junit.jupiter.api.Test;

/**
 * Proves a context that resolves to more than one candidate region fails explicitly instead of
 * guessing one of them. Two "Shipping" regions exist; a context is a hard scope, not a scoring
 * bonus, so resolving it must throw {@link AmbiguousLocatorException} rather than silently
 * continuing inside the first-found region. Context resolution happens eagerly when {@code
 * page.find(context)} is called (before any action is built or executed), so the exception
 * propagates directly from that call. Independent proof comes from two separate server-side click
 * counters, one per region's button - both must stay at zero.
 */
class ContextWrongTargetProtectionIT {

    @Test
    void anAmbiguousContextFailsExplicitlyInsteadOfPickingARegion() throws Exception {
        try (var support = Phase4TestSupport.start();
                var page = support.open("/actions/context-ambiguous")) {
            assertThatThrownBy(
                            () ->
                                    page.find(
                                                    InteractionContext.context()
                                                            .containingText("Shipping"))
                                            .button()
                                            .named("Continue")
                                            .reference())
                    .isInstanceOf(AmbiguousLocatorException.class);

            assertThat(support.clickCount("shipping-1")).isZero();
            assertThat(support.clickCount("shipping-2")).isZero();
        }
    }
}
