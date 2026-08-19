package io.webagent4j.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.webagent4j.action.ActionFailureType;
import io.webagent4j.action.ActionResult;
import io.webagent4j.browser.InteractionContext;
import io.webagent4j.dom.IElement;
import org.junit.jupiter.api.Test;

/**
 * Proves an explicit element scope that was valid when obtained, but has been detached from the
 * document by the time the terminal resolution runs, fails explicitly instead of silently falling
 * back to the parent scope, the page, or any semantically similar replacement. Only a semantic
 * {@code IElementReference}/structured scope is entitled to that kind of re-resolution; a concrete
 * explicit {@code IElement} is the caller's deliberate choice of one specific node, and its
 * disappearance is a hard failure, never quietly reinterpreted as "search elsewhere".
 */
class ExplicitScopeDetachmentProtectionIT {

    @Test
    void aDetachedExplicitChildScopeFailsInsteadOfFallingBackToTheParent() throws Exception {
        try (var support = Phase4TestSupport.start();
                var page = support.open("/actions/mixed-scope-detached-child")) {
            IElement outerContainer = page.find().id("outer-container").single();
            var target =
                    page.find(InteractionContext.context().containingText("Product A"))
                            .within(outerContainer)
                            .button()
                            .named("Confirm")
                            .reference();

            page.evaluate("detachOuterContainer()");
            ActionResult<Void> result = page.action().click(target).execute();

            assertThat(result.success()).isFalse();
            assertThat(result.failure().orElseThrow().type())
                    .isEqualTo(ActionFailureType.TARGET_NOT_FOUND);
            assertThat(support.clickCount("product-a-confirm")).isZero();
        }
    }
}
