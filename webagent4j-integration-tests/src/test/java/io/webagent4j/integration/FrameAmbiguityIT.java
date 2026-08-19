package io.webagent4j.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import io.webagent4j.browser.IFrame;
import io.webagent4j.locator.AmbiguousLocatorException;
import io.webagent4j.locator.LocatorNotFoundException;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * Proves frame ambiguity is never silently resolved. Two frames sharing the same {@code name}
 * criterion fail as {@link AmbiguousLocatorException} rather than selecting the first one
 * Playwright happens to return or falling back to DOM order; two frames with an identical target
 * inside each (both have a "Buy" button) are still resolved correctly per frame, proving frame
 * boundaries stay isolated instead of one frame's target leaking into a query scoped to the other;
 * and a frame that becomes ambiguous while a bounded wait is still polling ends that wait as
 * ambiguous, never by silently continuing to wait for it to resolve itself.
 */
class FrameAmbiguityIT {

    @Test
    void twoFramesSharingTheSameNameCriterionAreAmbiguous() throws Exception {
        try (var support = FramePhase4TestSupport.start();
                var page = support.open("/frames/two-identical-payment")) {
            assertThatExceptionOfType(AmbiguousLocatorException.class)
                    .isThrownBy(() -> page.frame().named("payment").single());
        }
    }

    @Test
    void identicalTargetsInTwoFramesResolveOnlyWithinTheirOwnFrame() throws Exception {
        try (var support = FramePhase4TestSupport.start();
                var page = support.open("/frames/wrong-frame-buy")) {
            IFrame productA = page.frame().named("product-a").single();
            IFrame productB = page.frame().named("product-b").single();

            productA.action()
                    .click(productA.find().button().named("Buy").reference())
                    .execute()
                    .throwIfFailed();

            assertThat(support.clickCount("product-a-buy")).isEqualTo(1);
            assertThat(support.clickCount("product-b-buy")).isZero();

            productB.action()
                    .click(productB.find().button().named("Buy").reference())
                    .execute()
                    .throwIfFailed();

            assertThat(support.clickCount("product-a-buy")).isEqualTo(1);
            assertThat(support.clickCount("product-b-buy")).isEqualTo(1);
        }
    }

    /**
     * A "payment" frame uniquely resolves at t=0, so a bare {@code timeout(...)} wait would already
     * have returned before the duplicate frame is inserted 150ms later. {@code stableFor(300ms)}
     * keeps the wait actively polling through that moment - exactly {@link
     * io.webagent4j.locator.api.ILocator#stableFor(Duration)}'s guarantee, applied to the frame
     * boundary - so the duplicate is genuinely observed mid-wait and fails the resolution
     * immediately as ambiguous, rather than the wait having already committed to the first frame.
     */
    @Test
    void aFrameThatBecomesAmbiguousWhileActivelyWaitingEndsTheWaitAsAmbiguous() throws Exception {
        try (var support = FramePhase4TestSupport.start();
                var page = support.open("/frames/becomes-ambiguous-during-wait")) {
            assertThatExceptionOfType(AmbiguousLocatorException.class)
                    .isThrownBy(
                            () ->
                                    page.frame()
                                            .named("payment")
                                            .stableFor(Duration.ofMillis(300))
                                            .timeout(Duration.ofMillis(800))
                                            .single());
        }
    }

    /**
     * The "checkout" frame and its "Pay" button both resolve at t=0, so the wait must be held open
     * with {@code stableFor(300ms)} - mirroring {@code DynamicContextDisappearanceDuringWaitIT} at
     * the element level - to still be actively polling when the underlying {@code <iframe>} is
     * removed 150ms later: every poll re-resolves the frame pending-scope fresh, so its removal
     * surfaces as a typed not-found on the very next attempt, never a stale element interaction.
     */
    @Test
    void aFrameThatDisappearsWhileActivelyWaitingForATargetInsideItEndsTheWaitAsNotFound()
            throws Exception {
        try (var support = FramePhase4TestSupport.start();
                var page = support.open("/frames/disappearing-during-wait")) {
            IFrame checkout = page.frame().named("checkout").single();

            assertThatExceptionOfType(LocatorNotFoundException.class)
                    .isThrownBy(
                            () ->
                                    checkout.find()
                                            .button()
                                            .named("Pay")
                                            .stableFor(Duration.ofMillis(300))
                                            .timeout(Duration.ofMillis(800))
                                            .single());

            assertThat(support.clickCount("checkout-pay")).isZero();
        }
    }
}
