package io.webagent4j.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.webagent4j.action.ActionFailureType;
import io.webagent4j.action.ActionResult;
import io.webagent4j.browser.InteractionContext;
import io.webagent4j.locator.AmbiguousLocatorException;
import org.junit.jupiter.api.Test;

/**
 * Proves a context that resolves to more than one candidate region blocks the action explicitly
 * instead of guessing one of them. Two "Shipping" regions exist from the start, so this exercises
 * the always-ambiguous case; {@link ContextBecomesAmbiguousBeforeActionIT} covers a context that
 * only becomes ambiguous after the reference was built. A structured scope is re-resolved at
 * terminal operation time (see {@code PlaywrightLocator}), never eagerly at {@code within(...)} or
 * {@code reference()} time, so the ambiguity surfaces as a structured {@link ActionFailureType} on
 * {@code execute()} rather than as an exception thrown while building the reference. Independent
 * proof comes from two separate server-side click counters, one per region's button - both must
 * stay at zero.
 */
class ContextWrongTargetProtectionIT {

    @Test
    void anAmbiguousContextBlocksTheActionInsteadOfPickingARegion() throws Exception {
        try (var support = Phase4TestSupport.start();
                var page = support.open("/actions/context-ambiguous")) {
            var target =
                    page.find(InteractionContext.context().containingText("Shipping"))
                            .button()
                            .named("Continue")
                            .reference();

            ActionResult<Void> result = page.action().click(target).execute();

            assertThat(result.success()).isFalse();
            assertThat(result.failure().orElseThrow().type())
                    .isEqualTo(ActionFailureType.TARGET_AMBIGUOUS);
            assertThat(support.clickCount("shipping-1")).isZero();
            assertThat(support.clickCount("shipping-2")).isZero();
        }
    }

    @Test
    void differentAccessibleNameSourcesRemainAmbiguous() throws Exception {
        try (var support = Phase4TestSupport.start();
                var page = support.open("/actions/context-cross-source-ambiguous")) {
            var target =
                    page.find(InteractionContext.context().containingText("Shipping"))
                            .button()
                            .named("Continue")
                            .reference();

            ActionResult<Void> result = page.action().click(target).execute();

            assertThat(result.success()).isFalse();
            assertThat(result.failure().orElseThrow().type())
                    .isEqualTo(ActionFailureType.TARGET_AMBIGUOUS);
            assertThat(result.failure().orElseThrow().cause())
                    .hasValueSatisfying(
                            failure ->
                                    assertThat(failure)
                                            .isInstanceOf(AmbiguousLocatorException.class));
            assertThat(support.clickCount("shipping-1")).isZero();
            assertThat(support.clickCount("shipping-2")).isZero();
        }
    }
}
