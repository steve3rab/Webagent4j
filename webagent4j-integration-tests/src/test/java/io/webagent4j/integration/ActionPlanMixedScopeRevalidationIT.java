package io.webagent4j.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.webagent4j.action.ActionPlanStatus;
import io.webagent4j.action.ActionResult;
import io.webagent4j.action.IActionPlan;
import io.webagent4j.browser.InteractionContext;
import io.webagent4j.dom.IElement;
import org.junit.jupiter.api.Test;

/**
 * Proves {@link IActionPlan#execute()} preserves declared mixed-scope order through a full
 * revalidation, not just a single structured scope in isolation. The reference is built as {@code
 * within(structured("Product A")).within(explicit(#outer-container))}; before {@code execute()}
 * runs, Product A's "Available" region (and its "Ajouter" button) is replaced by an equivalent new
 * node - {@code #outer-container} itself is untouched, so the explicit scope stays valid, and the
 * structured "Product A" scope is re-resolved fresh, in the same declared position, before the
 * target is searched again inside the (still valid) explicit container. The backend must run
 * exactly once, on Product A's button; Product B's identically-shaped button must never be touched.
 */
class ActionPlanMixedScopeRevalidationIT {

    @Test
    void aPlanBuiltFromAMixedScopeChainRevalidatesInTheSameDeclaredOrder() throws Exception {
        try (var support = Phase4TestSupport.start();
                var page = support.open("/actions/mixed-scope-product-dynamic")) {
            IElement outerContainerA = page.find().id("outer-container").single();
            var target =
                    page.find(InteractionContext.context().containingText("Product A"))
                            .within(outerContainerA)
                            .button()
                            .named("Ajouter")
                            .reference();

            IActionPlan<Void> plan = page.action().click(target).plan();
            assertThat(plan.status()).isEqualTo(ActionPlanStatus.READY);

            page.evaluate("replaceProductAAvailableRegion()");
            ActionResult<Void> result = plan.execute();

            assertThat(result.success()).isTrue();
            assertThat(support.clickCount("product-a-ajouter")).isEqualTo(1);
            assertThat(support.clickCount("product-b-ajouter")).isZero();
        }
    }
}
