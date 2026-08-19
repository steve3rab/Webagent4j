package io.webagent4j.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.webagent4j.action.ActionFailureType;
import io.webagent4j.action.ActionResult;
import io.webagent4j.browser.InteractionContext;
import io.webagent4j.dom.IElement;
import org.junit.jupiter.api.Test;

/**
 * Proves scope containment is validated at terminal resolution time, not only when the fluent chain
 * is built. At T0, {@code #panel} is a real descendant of "Product A" and the reference is built
 * from {@code within(structured("Product A")).within(panel)}. Before the action runs, {@code
 * #panel} - the same live DOM node, not a replacement - is moved so that it becomes a child of
 * "Product B" instead. The explicit scope must be re-validated against the then-current DOM at
 * resolution time and rejected, exactly as if it had never belonged to Product A - never accepted
 * because it once did.
 */
class ExplicitScopeMovedOutsideParentIT {

    @Test
    void aChildMovedOutsideItsDeclaredParentIsRejectedAtTerminalResolutionTime() throws Exception {
        try (var support = Phase4TestSupport.start();
                var page = support.open("/actions/mixed-scope-child-moved")) {
            IElement panel = page.find().id("panel").single();
            var target =
                    page.find(InteractionContext.context().containingText("Product A"))
                            .within(panel)
                            .button()
                            .named("Confirm")
                            .reference();

            page.evaluate("movePanelToProductB()");
            ActionResult<Void> result = page.action().click(target).execute();

            assertThat(result.success()).isFalse();
            assertThat(result.failure().orElseThrow().type())
                    .isEqualTo(ActionFailureType.TARGET_NOT_FOUND);
            assertThat(support.clickCount("panel-confirm")).isZero();
        }
    }
}
