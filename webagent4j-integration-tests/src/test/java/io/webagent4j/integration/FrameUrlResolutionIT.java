package io.webagent4j.integration;

import static io.webagent4j.verification.Verifications.textVisible;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import io.webagent4j.browser.IFrame;
import io.webagent4j.locator.AmbiguousLocatorException;
import io.webagent4j.locator.LocatorNotFoundException;
import io.webagent4j.locator.api.TextMatch;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * Proves the {@code url} frame criterion genuinely participates in resolving which frame is meant,
 * disambiguating two frames that share every other declared criterion, instead of {@code id}/{@code
 * name}/{@code title} being resolved to a single winner and classified before {@code url} ever gets
 * a chance to narrow the match.
 */
class FrameUrlResolutionIT {

    @Test
    void aUrlCriterionDisambiguatesTwoFramesSharingTheSameNameByExactUrl() throws Exception {
        try (var support = FramePhase4TestSupport.start();
                var page = support.open("/frames/two-identical-payment")) {
            IFrame payment =
                    page.frame()
                            .named("payment")
                            .withUrl(TextMatch.exact(support.url("/frames/child/payment-2")))
                            .single();

            payment.action()
                    .click(payment.find().button().named("Pay").reference())
                    .expect(textVisible("Done"))
                    .execute()
                    .throwIfFailed();

            assertThat(support.clickCount("payment-2-pay")).isEqualTo(1);
            assertThat(support.clickCount("payment-1-pay")).isZero();
        }
    }

    @Test
    void twoFramesSharingBothNameAndUrlAreStillAmbiguous() throws Exception {
        try (var support = FramePhase4TestSupport.start();
                var page = support.open("/frames/two-identical-payment-same-url")) {
            assertThatExceptionOfType(AmbiguousLocatorException.class)
                    .isThrownBy(
                            () ->
                                    page.frame()
                                            .named("payment")
                                            .withUrl(
                                                    TextMatch.containing("/frames/child/payment-1"))
                                            .single());
        }
    }

    @Test
    void aUrlCriterionAloneSelectsOneFrameAmongSeveralWithoutAnyOtherCriterion() throws Exception {
        try (var support = FramePhase4TestSupport.start();
                var page = support.open("/frames/wrong-frame-buy")) {
            IFrame productB =
                    page.frame().withUrl(TextMatch.containing("/frames/child/buy-b")).single();

            productB.action()
                    .click(productB.find().button().named("Buy").reference())
                    .expect(textVisible("Done"))
                    .execute()
                    .throwIfFailed();

            assertThat(support.clickCount("product-b-buy")).isEqualTo(1);
            assertThat(support.clickCount("product-a-buy")).isZero();
        }
    }

    @Test
    void aNonexistentUrlCriterionFailsAsTypedNotFound() throws Exception {
        try (var support = FramePhase4TestSupport.start();
                var page = support.open("/frames/simple")) {
            assertThatExceptionOfType(LocatorNotFoundException.class)
                    .isThrownBy(
                            () ->
                                    page.frame()
                                            .withUrl(
                                                    TextMatch.exact(
                                                            support.url(
                                                                    "/frames/child/does-not-exist")))
                                            .timeout(Duration.ofMillis(500))
                                            .single());
        }
    }

    @Test
    void aReplacedFrameRetainsItsUrlBasedSemanticIdentity() throws Exception {
        try (var support = FramePhase4TestSupport.start();
                var page = support.open("/frames/replace-on-call")) {
            IFrame checkout =
                    page.frame()
                            .named("checkout")
                            .withUrl(TextMatch.containing("/frames/child/checkout"))
                            .single();
            assertThat(checkout.url()).contains("/frames/child/checkout");

            page.evaluate("replaceCheckoutFrame()");

            assertThat(checkout.url()).contains("/frames/child/checkout-v2");
            checkout.action()
                    .click(checkout.find().button().named("Pay").reference())
                    .expect(textVisible("Done"))
                    .execute()
                    .throwIfFailed();

            assertThat(support.clickCount("checkout-v2-pay")).isEqualTo(1);
        }
    }

    @Test
    void aNestedFrameResolvesWithAUrlCriterion() throws Exception {
        try (var support = FramePhase4TestSupport.start();
                var page = support.open("/frames/nested")) {
            IFrame outer = page.frame().named("outer").single();
            IFrame inner =
                    outer.frame()
                            .withUrl(TextMatch.containing("/frames/child/nested-inner"))
                            .single();

            assertThat(inner.find().button().named("Pay").single().accessibleName())
                    .isEqualTo("Pay");
        }
    }
}
