package io.webagent4j.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.webagent4j.action.ActionFailureType;
import io.webagent4j.action.ActionPlanStatus;
import io.webagent4j.action.ActionResult;
import io.webagent4j.action.IActionPlan;
import io.webagent4j.browser.InteractionContext;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * Proves {@link IActionPlan#execute()} revalidates a structured context, not just the target,
 * against live DOM state. The plan is built while exactly one "Shipping" region exists, so it is
 * {@link ActionPlanStatus#READY}; a duplicate region then appears before {@code execute()} runs.
 * Revalidation must resolve the context fresh and fail {@code TARGET_AMBIGUOUS} - a plan built
 * against a since-become-ambiguous context can never be executed. Independent proof comes from the
 * fixture's own server-side click counters, which must stay at zero.
 */
class ActionPlanContextInvalidationIT {

    @Test
    void aPlanWhoseContextBecomesAmbiguousBeforeExecuteNeverTouchesTheBackend() throws Exception {
        try (var support = Phase4TestSupport.start();
                var page = support.open("/actions/context-dynamic-ambiguous")) {
            var target =
                    page.find(InteractionContext.context().containingText("Shipping"))
                            .button()
                            .named("Continue")
                            .reference();

            IActionPlan<Void> plan = page.action().click(target).plan();
            assertThat(plan.status()).isEqualTo(ActionPlanStatus.READY);

            page.action().waitFor(Duration.ofMillis(300)).execute().throwIfFailed();
            ActionResult<Void> result = plan.execute();

            assertThat(result.success()).isFalse();
            assertThat(result.failure().orElseThrow().type())
                    .isEqualTo(ActionFailureType.TARGET_AMBIGUOUS);
            assertThat(support.clickCount("shipping-1")).isZero();
            assertThat(support.clickCount("shipping-2")).isZero();
        }
    }
}
