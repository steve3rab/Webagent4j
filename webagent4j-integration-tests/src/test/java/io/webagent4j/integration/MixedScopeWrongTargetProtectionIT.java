package io.webagent4j.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.webagent4j.action.ActionFailureType;
import io.webagent4j.action.ActionResult;
import io.webagent4j.browser.InteractionContext;
import io.webagent4j.dom.IElement;
import org.junit.jupiter.api.Test;

/**
 * The most important test in this suite: proves an explicit element scope declared after a
 * structured scope cannot escape it, even when the foreign element contains a perfectly valid,
 * uniquely-resolvable target of its own - so a weaker test that only proves {@code NOT_FOUND}
 * against a scope with no matching target at all would not be enough.
 *
 * <p>{@code within(...)} is a conjunction of nested constraints, never a replacement: {@code
 * within(structured("Product A")).within(explicit)} means "the explicit element, and it must be
 * proven to be inside Product A" - not "the explicit element, regardless of Product A". Here the
 * explicit element is {@code #other-container}, which belongs entirely to Product B: it is not a
 * descendant of Product A, and it contains its own real, unambiguous "Ajouter" button. An
 * implementation that let a later explicit scope silently override an earlier structured one -
 * exactly what this codebase did before containment was enforced - would have resolved this chain
 * successfully and clicked Product B's button. The current implementation must instead prove the
 * containment relationship before ever running the target lookup, fail {@code TARGET_NOT_FOUND}
 * because {@code #other-container} is not inside Product A, and never touch either button.
 * Independent proof comes from two separate server-side click counters.
 */
class MixedScopeWrongTargetProtectionIT {

    @Test
    void anExplicitElementFromAForeignRegionIsRejectedEvenThoughItContainsAValidTarget()
            throws Exception {
        try (var support = Phase4TestSupport.start();
                var page = support.open("/actions/mixed-scope-product")) {
            IElement otherContainerFromProductB = page.find().id("other-container").single();

            ActionResult<Void> result =
                    page.action()
                            .click(
                                    page.find(
                                                    InteractionContext.context()
                                                            .containingText("Product A"))
                                            .within(otherContainerFromProductB)
                                            .button()
                                            .named("Ajouter")
                                            .reference())
                            .execute();

            assertThat(result.success()).isFalse();
            assertThat(result.failure().orElseThrow().type())
                    .isEqualTo(ActionFailureType.TARGET_NOT_FOUND);
            assertThat(support.clickCount("product-a-ajouter")).isZero();
            assertThat(support.clickCount("product-b-ajouter")).isZero();
        }
    }
}
