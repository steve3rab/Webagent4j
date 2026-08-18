package io.webagent4j.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.webagent4j.action.ActionFailureType;
import io.webagent4j.action.ActionResult;
import io.webagent4j.browser.InteractionContext;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * Proves that a structured context which existed uniquely when the reference was built, but is
 * entirely removed from the DOM before the action runs, blocks the action instead of reusing
 * whatever region resolved earlier. Independent proof comes from the fixture's own server-side
 * click counter, which must stay at zero.
 */
class ContextDisappearsBeforeActionIT {

    @Test
    void aContextThatDisappearsAfterTheReferenceIsBuiltBlocksTheAction() throws Exception {
        try (var support = Phase4TestSupport.start();
                var page = support.open("/actions/context-dynamic-disappears")) {
            var target =
                    page.find(InteractionContext.context().containingText("Shipping"))
                            .button()
                            .named("Continue")
                            .reference();

            page.action().waitFor(Duration.ofMillis(300)).execute().throwIfFailed();
            ActionResult<Void> result = page.action().click(target).execute();

            assertThat(result.success()).isFalse();
            assertThat(result.failure().orElseThrow().type())
                    .isEqualTo(ActionFailureType.TARGET_NOT_FOUND);
            assertThat(support.clickCount("shipping-solo")).isZero();
        }
    }
}
