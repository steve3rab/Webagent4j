package io.webagent4j.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.webagent4j.action.ActionFailureType;
import io.webagent4j.action.ActionPlanStatus;
import io.webagent4j.action.ActionResult;
import io.webagent4j.action.IActionPlan;
import io.webagent4j.browser.InteractionContext;
import io.webagent4j.dom.IElement;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * Proves {@link IActionPlan#execute()} revalidates the whole scope chain, including the containment
 * relationship between a structured scope and a later explicit element scope - not only whether the
 * target itself still resolves. At T0, {@code #panel} is a real descendant of "Product A" and the
 * plan is built from {@code within(structured("Product A")).within(panel)}, so it is {@link
 * ActionPlanStatus#READY}. Before {@code execute()} runs, {@code #panel} is moved so that it
 * becomes a child of "Product B" instead - the same live node, not a replacement. Revalidation must
 * re-derive the containment relationship against the then-current DOM and reject it, exactly as it
 * would if the chain had never been declared READY.
 */
class ActionPlanScopeContainmentRevalidationIT {

    @Test
    void aPlanWhoseExplicitChildIsMovedOutsideItsParentFailsInsteadOfExecuting() throws Exception {
        try (var support = Phase4TestSupport.start();
                var page = support.open("/actions/mixed-scope-child-moved")) {
            IElement panel = page.find().id("panel").single();
            var target =
                    page.find(InteractionContext.context().containingText("Product A"))
                            .within(panel)
                            .button()
                            .named("Confirm")
                            .reference();

            IActionPlan<Void> plan = page.action().click(target).plan();
            assertThat(plan.status()).isEqualTo(ActionPlanStatus.READY);

            page.action().waitFor(Duration.ofMillis(300)).execute().throwIfFailed();
            ActionResult<Void> result = plan.execute();

            assertThat(result.success()).isFalse();
            assertThat(result.failure().orElseThrow().type())
                    .isEqualTo(ActionFailureType.TARGET_NOT_FOUND);
            assertThat(support.clickCount("panel-confirm")).isZero();
        }
    }
}
