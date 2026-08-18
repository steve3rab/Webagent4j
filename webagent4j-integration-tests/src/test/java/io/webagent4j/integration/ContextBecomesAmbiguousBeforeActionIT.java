package io.webagent4j.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.webagent4j.action.ActionFailureType;
import io.webagent4j.action.ActionResult;
import io.webagent4j.browser.InteractionContext;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * Proves a structured context that was unique when the reference was built, but becomes ambiguous
 * before the action actually runs, blocks the action instead of silently reusing whichever region
 * resolved first. The reference is built while exactly one "Shipping" region exists; a duplicate
 * region then appears; only afterwards does the action execute. Because a structured scope is
 * re-resolved fresh at terminal operation time - never frozen into a single node when the fluent
 * chain was built - this must fail {@code TARGET_AMBIGUOUS} rather than reuse the region resolved
 * before the duplicate appeared. Independent proof comes from server-side click counters, one per
 * candidate region or sub-region; all must stay at zero.
 */
class ContextBecomesAmbiguousBeforeActionIT {

    @Test
    void aContextThatBecomesAmbiguousAfterTheReferenceIsBuiltBlocksTheAction() throws Exception {
        try (var support = Phase4TestSupport.start();
                var page = support.open("/actions/context-dynamic-ambiguous")) {
            var target =
                    page.find(InteractionContext.context().containingText("Shipping"))
                            .button()
                            .named("Continue")
                            .reference();

            page.action().waitFor(Duration.ofMillis(300)).execute().throwIfFailed();
            ActionResult<Void> result = page.action().click(target).execute();

            assertThat(result.success()).isFalse();
            assertThat(result.failure().orElseThrow().type())
                    .isEqualTo(ActionFailureType.TARGET_AMBIGUOUS);
            assertThat(support.clickCount("shipping-1")).isZero();
            assertThat(support.clickCount("shipping-2")).isZero();
        }
    }

    @Test
    void aSecondNestedConstraintThatBecomesAmbiguousAfterTheReferenceIsBuiltBlocksTheAction()
            throws Exception {
        try (var support = Phase4TestSupport.start();
                var page = support.open("/actions/context-dynamic-nested-ambiguous")) {
            var target =
                    page.find(
                                    InteractionContext.context()
                                            .containingText("Laptop B")
                                            .containingText("Available"))
                            .button()
                            .named("Ajouter")
                            .reference();

            page.action().waitFor(Duration.ofMillis(300)).execute().throwIfFailed();
            ActionResult<Void> result = page.action().click(target).execute();

            assertThat(result.success()).isFalse();
            assertThat(result.failure().orElseThrow().type())
                    .isEqualTo(ActionFailureType.TARGET_AMBIGUOUS);
            assertThat(support.clickCount("laptopB-available")).isZero();
            assertThat(support.clickCount("laptopB-available-2")).isZero();
        }
    }
}
