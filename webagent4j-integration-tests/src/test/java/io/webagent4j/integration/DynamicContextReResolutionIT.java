package io.webagent4j.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.webagent4j.browser.InteractionContext;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * Proves a structured context is genuinely re-resolved against live DOM state at action time,
 * rather than reused from whatever concrete node it resolved to when the reference was built. The
 * "unchanged" case is the baseline: nothing moves between reference creation and execution, and the
 * action still succeeds on the one correct target. The "replaced" case is the interesting one - the
 * entire context region, and the target inside it, are swapped for new DOM nodes carrying the same
 * semantics ({@code aria-label="Shipping"}, a button named "Continue") - and the action must still
 * resolve and click exactly the new node, proving re-resolution is real and not a stale cached
 * lookup. Independent proof comes from the fixture's own server-side click counter.
 */
class DynamicContextReResolutionIT {

    @Test
    void anUnchangedContextResolvesAndExecutesNormally() throws Exception {
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
        }
    }

    @Test
    void aContextAndTargetReplacedWithTheSameSemanticsAreReResolvedAndExecutedExactlyOnce()
            throws Exception {
        try (var support = Phase4TestSupport.start();
                var page = support.open("/actions/context-dynamic-replaced")) {
            var target =
                    page.find(InteractionContext.context().containingText("Shipping"))
                            .button()
                            .named("Continue")
                            .reference();

            page.action().waitFor(Duration.ofMillis(300)).execute().throwIfFailed();
            page.action().click(target).execute().throwIfFailed();

            assertThat(support.clickCount("shipping-continue")).isEqualTo(1);
        }
    }
}
