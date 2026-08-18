package io.webagent4j.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.webagent4j.browser.InteractionContext;
import io.webagent4j.dom.IElement;
import io.webagent4j.locator.LocatorNotFoundException;
import org.junit.jupiter.api.Test;

/**
 * Proves a mixed chain of a structured scope and an explicit element scope resolves in exactly the
 * order the caller declared, not the reverse. "Product A" contains {@code #outer-container}, which
 * in turn contains its own "Available" region and "Ajouter" button; "Product B" has an identical
 * structure under {@code #other-container}. {@code within(structured).within(explicit)} narrows to
 * Product A, then to its own {@code #outer-container} - a real descendant relationship, so it
 * succeeds and clicks Product A's button. Reversing the declared order to {@code
 * within(explicit).within(structured)} means "inside #outer-container, find a region labelled
 * Product A" - but Product A is an ancestor of #outer-container, not a descendant, so it fails
 * explicitly instead of silently reusing the same target or falling back to an unscoped search.
 * Independent proof comes from two separate server-side click counters, one per product.
 */
class MixedScopeOrderingIT {

    @Test
    void structuredThenExplicitResolvesInsideTheDeclaredNesting() throws Exception {
        try (var support = Phase4TestSupport.start();
                var page = support.open("/actions/mixed-scope-product")) {
            IElement outerContainerA = page.find().id("outer-container").single();

            page.action()
                    .click(
                            page.find(InteractionContext.context().containingText("Product A"))
                                    .within(outerContainerA)
                                    .button()
                                    .named("Ajouter")
                                    .reference())
                    .execute()
                    .throwIfFailed();

            assertThat(support.clickCount("product-a-ajouter")).isEqualTo(1);
            assertThat(support.clickCount("product-b-ajouter")).isZero();
        }
    }

    @Test
    void explicitThenStructuredDoesNotRepresentTheSameSearchAsTheReverseOrder() throws Exception {
        try (var support = Phase4TestSupport.start();
                var page = support.open("/actions/mixed-scope-product")) {
            IElement outerContainerA = page.find().id("outer-container").single();

            assertThatThrownBy(
                            () ->
                                    page.find()
                                            .within(outerContainerA)
                                            .within(
                                                    InteractionContext.context()
                                                            .containingText("Product A"))
                                            .button()
                                            .named("Ajouter")
                                            .single())
                    .isInstanceOf(LocatorNotFoundException.class);

            assertThat(support.clickCount("product-a-ajouter")).isZero();
            assertThat(support.clickCount("product-b-ajouter")).isZero();
        }
    }
}
