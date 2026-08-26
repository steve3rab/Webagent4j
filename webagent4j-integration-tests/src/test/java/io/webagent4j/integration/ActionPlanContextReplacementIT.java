package io.webagent4j.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.webagent4j.action.ActionPlanStatus;
import io.webagent4j.action.ActionResult;
import io.webagent4j.action.IActionPlan;
import io.webagent4j.browser.InteractionContext;
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
                var page = support.open("/actions/context-scope-replace-before-use")) {

            var target =
                    page.find(InteractionContext.context().containingText("Shipping"))
                            .button()
                            .named("Continue")
                            .reference();

            IActionPlan<Void> plan = page.action().click(target).plan();

            assertThat(plan.status()).isEqualTo(ActionPlanStatus.READY);

            page.evaluate(
                    """
                    () => {
                      const old = document.getElementById('shipping-original');

                      const fresh = document.createElement('section');
                      fresh.id = 'shipping-replacement';
                      fresh.setAttribute('aria-label', 'Shipping');
                      fresh.innerHTML = '<button>Continue</button>';

                      fresh.querySelector('button').addEventListener(
                          'click',
                          () => fetch('/count-click/shipping-replacement'));

                      old.replaceWith(fresh);
                    }
                    """);

            ActionResult<Void> result = plan.execute();

            assertThat(result.success()).isTrue();

            support.awaitClickCount("shipping-replacement", 1);

            assertThat(support.clickCount("shipping-original")).isZero();
            assertThat(support.clickCount("shipping-replacement")).isEqualTo(1);
        }
    }
}
