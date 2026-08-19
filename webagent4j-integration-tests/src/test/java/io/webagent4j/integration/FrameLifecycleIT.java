package io.webagent4j.integration;

import static io.webagent4j.verification.Verifications.textVisible;
import static org.assertj.core.api.Assertions.assertThat;

import io.webagent4j.browser.IFrame;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * Proves two frame lifecycle contracts that don't require an active poll: a frame inserted into the
 * document after resolution begins is still found within the caller's bounded timeout (delayed
 * insertion), and an {@link IFrame} handle obtained before its underlying {@code <iframe>} is
 * removed-and-replaced by a new one with the same semantic identity transparently follows the
 * replacement on its next use, rather than reusing a stale document reference - because the
 * handle's identity is a re-resolvable frame criterion, never a frozen Playwright {@code Frame}
 * snapshot.
 */
class FrameLifecycleIT {

    @Test
    void aFrameInsertedAfterResolutionBeginsIsFoundWithinTheBoundedTimeout() throws Exception {
        try (var support = FramePhase4TestSupport.start();
                var page = support.open("/frames/delayed-insert")) {
            IFrame checkout =
                    page.frame().named("checkout").timeout(Duration.ofMillis(800)).single();

            assertThat(checkout.url()).contains("/frames/child/checkout");
        }
    }

    @Test
    void aFrameHandleTransparentlyFollowsReplacementBySameSemanticIdentity() throws Exception {
        try (var support = FramePhase4TestSupport.start();
                var page = support.open("/frames/replace-on-call")) {
            IFrame checkout = page.frame().named("checkout").single();
            assertThat(checkout.url()).contains("/frames/child/checkout");

            page.evaluate("replaceCheckoutFrame()");

            assertThat(checkout.url()).contains("/frames/child/checkout-v2");
            checkout.action()
                    .click(checkout.find().button().named("Pay").reference())
                    .expect(textVisible("Done"))
                    .execute()
                    .throwIfFailed();

            assertThat(support.clickCount("checkout-v2-pay")).isEqualTo(1);
            assertThat(support.clickCount("checkout-pay")).isZero();
        }
    }
}
