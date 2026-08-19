package io.webagent4j.integration;

import static io.webagent4j.verification.Verifications.textVisible;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import io.webagent4j.browser.IFrame;
import io.webagent4j.locator.AmbiguousLocatorException;
import io.webagent4j.locator.LocatorNotFoundException;
import io.webagent4j.locator.LocatorResult;
import io.webagent4j.locator.api.ElementRole;
import io.webagent4j.locator.api.LocatorDefinition;
import io.webagent4j.locator.api.TextMatch;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * Proves {@code IFrame#locate(LocatorDefinition)} re-resolves its own frame's pending-scope chain
 * fresh on every poll, exactly like {@code frame.find()...single()} already does, rather than
 * resolving the frame once before the wait for the element definition begins. A programmatic {@code
 * locate(...)} call and an equivalent fluent {@code find()...single()} call must behave identically
 * across frame replacement, disappearance, and growing ambiguity - there is no second, less-live
 * resolution path hiding behind the programmatic entry point.
 */
class FrameLocateLiveResolutionIT {

    private static final LocatorDefinition PAY_BUTTON =
            LocatorDefinition.forRole(ElementRole.BUTTON)
                    .withAccessibleName(TextMatch.exactIgnoringCase("Pay"));

    @Test
    void locateSucceedsAfterTheUnderlyingFrameIsReplacedBySameSemanticIdentity() throws Exception {
        try (var support = FramePhase4TestSupport.start();
                var page = support.open("/frames/replace-on-call")) {
            IFrame checkout = page.frame().named("checkout").single();

            page.evaluate("replaceCheckoutFrame()");

            LocatorResult resolved = checkout.locate(PAY_BUTTON);

            checkout.action()
                    .click(resolved.element())
                    .expect(textVisible("Done"))
                    .execute()
                    .throwIfFailed();
            assertThat(support.clickCount("checkout-v2-pay")).isEqualTo(1);
            assertThat(support.clickCount("checkout-pay")).isZero();
        }
    }

    /**
     * Mirrors {@code FrameAmbiguityIT}'s disappearance-during-wait scenario, but through {@code
     * locate(...)} rather than {@code find()...single()}: the frame is present at t=0, so a bare
     * timeout would already have returned before the removal fires 150ms later; {@code stableFor}
     * keeps the wait actively polling through that moment, proving {@code locate(...)} re-resolves
     * the frame's own pending scope on every attempt too.
     */
    @Test
    void locateFailsAsNotFoundWhenTheFrameDisappearsDuringTheWait() throws Exception {
        try (var support = FramePhase4TestSupport.start();
                var page = support.open("/frames/disappearing-during-wait")) {
            IFrame checkout =
                    page.frame().named("checkout").timeout(Duration.ofSeconds(15)).single();

            assertThatExceptionOfType(LocatorNotFoundException.class)
                    .isThrownBy(
                            () ->
                                    checkout.locate(
                                            PAY_BUTTON
                                                    .stableFor(Duration.ofMillis(300))
                                                    .withTimeout(Duration.ofMillis(800))));

            assertThat(support.clickCount("checkout-pay")).isZero();
        }
    }

    /**
     * Mirrors {@code FrameAmbiguityIT}'s becomes-ambiguous-during-wait scenario through {@code
     * locate(...)}: a duplicate "payment" frame is inserted 150ms after the wait begins, and {@code
     * stableFor} keeps the wait actively polling so the duplicate is genuinely observed mid-wait.
     */
    @Test
    void locateFailsAsAmbiguousWhenASecondIdenticalFrameAppearsDuringStableFor() throws Exception {
        try (var support = FramePhase4TestSupport.start();
                var page = support.open("/frames/becomes-ambiguous-during-wait")) {
            IFrame payment = page.frame().named("payment").timeout(Duration.ofSeconds(15)).single();

            assertThatExceptionOfType(AmbiguousLocatorException.class)
                    .isThrownBy(
                            () ->
                                    payment.locate(
                                            PAY_BUTTON
                                                    .stableFor(Duration.ofMillis(300))
                                                    .withTimeout(Duration.ofMillis(800))));
        }
    }

    @Test
    void locateCorrectlyReResolvesANestedFrame() throws Exception {
        try (var support = FramePhase4TestSupport.start();
                var page = support.open("/frames/nested")) {
            IFrame outer = page.frame().named("outer").single();
            IFrame inner = outer.frame().named("inner").single();

            LocatorResult resolved = inner.locate(PAY_BUTTON);

            assertThat(resolved.element().accessibleName()).isEqualTo("Pay");
        }
    }

    @Test
    void locateNeverLeaksAWrongTargetFromASiblingFrame() throws Exception {
        try (var support = FramePhase4TestSupport.start();
                var page = support.open("/frames/wrong-frame-buy")) {
            IFrame productA = page.frame().named("product-a").single();
            IFrame productB = page.frame().named("product-b").single();
            LocatorDefinition buyButton =
                    LocatorDefinition.forRole(ElementRole.BUTTON)
                            .withAccessibleName(TextMatch.exactIgnoringCase("Buy"));

            LocatorResult resolvedFromA = productA.locate(buyButton);
            productA.action()
                    .click(resolvedFromA.element())
                    .expect(textVisible("Done"))
                    .execute()
                    .throwIfFailed();

            assertThat(support.clickCount("product-a-buy")).isEqualTo(1);
            assertThat(support.clickCount("product-b-buy")).isZero();

            LocatorResult resolvedFromB = productB.locate(buyButton);
            productB.action()
                    .click(resolvedFromB.element())
                    .expect(textVisible("Done"))
                    .execute()
                    .throwIfFailed();

            assertThat(support.clickCount("product-a-buy")).isEqualTo(1);
            assertThat(support.clickCount("product-b-buy")).isEqualTo(1);
        }
    }
}
