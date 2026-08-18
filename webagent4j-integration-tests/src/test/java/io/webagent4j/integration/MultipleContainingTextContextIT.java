package io.webagent4j.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.webagent4j.browser.InteractionContext;
import org.junit.jupiter.api.Test;

/**
 * Proves every {@code containingText} constraint on an {@link InteractionContext} is honored, in
 * order, rather than only the first one. The fixture has four otherwise-identical "Ajouter" buttons
 * (two products, each with an available and an unavailable row); only combining both constraints
 * narrows the search to the single correct one. Independent proof comes from four separate
 * server-side click counters, one per button, not from the library's own success verdict.
 */
class MultipleContainingTextContextIT {

    @Test
    void combinesBothConstraintsToSelectExactlyTheRightTarget() throws Exception {
        try (var support = Phase4TestSupport.start();
                var page = support.open("/actions/context-multi")) {
            page.action()
                    .click(
                            page.find(
                                            InteractionContext.context()
                                                    .containingText("Laptop B")
                                                    .containingText("Available"))
                                    .button()
                                    .named("Ajouter")
                                    .reference())
                    .execute()
                    .throwIfFailed();

            assertThat(support.clickCount("laptopB-available")).isEqualTo(1);
            assertThat(support.clickCount("laptopB-unavailable")).isZero();
            assertThat(support.clickCount("laptopA-available")).isZero();
            assertThat(support.clickCount("laptopA-unavailable")).isZero();
        }
    }

    @Test
    void reversingTheConstraintOrderStillSelectsTheRightTarget() throws Exception {
        try (var support = Phase4TestSupport.start();
                var page = support.open("/actions/context-multi")) {
            page.action()
                    .click(
                            page.find(
                                            InteractionContext.context()
                                                    .containingText("Laptop A")
                                                    .containingText("Unavailable"))
                                    .button()
                                    .named("Ajouter")
                                    .reference())
                    .execute()
                    .throwIfFailed();

            assertThat(support.clickCount("laptopA-unavailable")).isEqualTo(1);
            assertThat(support.clickCount("laptopA-available")).isZero();
            assertThat(support.clickCount("laptopB-available")).isZero();
            assertThat(support.clickCount("laptopB-unavailable")).isZero();
        }
    }
}
