package io.webagent4j.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.webagent4j.action.ActionFailureType;
import io.webagent4j.action.ActionResult;
import io.webagent4j.browser.InteractionContext;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * Proves the most dangerous dynamic-context scenario: the "Shipping" region the reference was built
 * against is removed and replaced by an unrelated "Billing" region that happens to contain an
 * identically-named "Continue" button. Re-resolving "Shipping" must report {@code TARGET_NOT_FOUND}
 * - Shipping no longer exists - and must never fall through to matching "Continue" inside the
 * semantically different Billing region. This falls out of the same re-resolution mechanism proven
 * elsewhere: no special-casing is needed to keep it safe. Independent proof comes from two separate
 * server-side click counters; both must stay at zero.
 */
class ContextSemanticChangeWrongTargetProtectionIT {

    @Test
    void aContextReplacedByADifferentSemanticRegionNeverRedirectsTheAction() throws Exception {
        try (var support = Phase4TestSupport.start();
                var page = support.open("/actions/context-dynamic-semantic-change")) {
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
            assertThat(support.clickCount("shipping-continue")).isZero();
            assertThat(support.clickCount("billing-continue")).isZero();
        }
    }
}
