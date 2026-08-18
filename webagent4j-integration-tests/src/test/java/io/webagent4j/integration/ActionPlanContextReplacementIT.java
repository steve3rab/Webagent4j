package io.webagent4j.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.webagent4j.action.ActionPlanStatus;
import io.webagent4j.action.ActionResult;
import io.webagent4j.action.IActionPlan;
import io.webagent4j.browser.InteractionContext;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * Proves {@link IActionPlan#execute()} can still succeed after both the context region and the
 * target inside it were replaced by new DOM nodes carrying the same semantics ({@code
 * aria-label="Shipping"}, a button named "Continue"). Revalidation re-resolves the context fresh
 * rather than trusting the plan()-time snapshot, so the same semantic target is found again and the
 * backend runs exactly once on the correct, newly-inserted node. Independent proof comes from the
 * fixture's own server-side click counter.
 */
class ActionPlanContextReplacementIT {

    @Test
    void aPlanWhoseContextIsReplacedWithTheSameSemanticsStillExecutesExactlyOnce()
            throws Exception {
        try (var support = Phase4TestSupport.start();
                var page = support.open("/actions/context-dynamic-replaced")) {
            var target =
                    page.find(InteractionContext.context().containingText("Shipping"))
                            .button()
                            .named("Continue")
                            .reference();

            IActionPlan<Void> plan = page.action().click(target).plan();
            assertThat(plan.status()).isEqualTo(ActionPlanStatus.READY);

            page.action().waitFor(Duration.ofMillis(300)).execute().throwIfFailed();
            ActionResult<Void> result = plan.execute();

            assertThat(result.success()).isTrue();
            assertThat(support.clickCount("shipping-continue")).isEqualTo(1);
        }
    }
}
