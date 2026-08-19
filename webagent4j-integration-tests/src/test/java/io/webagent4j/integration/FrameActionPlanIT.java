package io.webagent4j.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.webagent4j.action.ActionFailureType;
import io.webagent4j.action.ActionPlanStatus;
import io.webagent4j.action.ActionResult;
import io.webagent4j.action.IActionPlan;
import io.webagent4j.browser.IFrame;
import io.webagent4j.dom.IElement;
import io.webagent4j.locator.api.IElementReference;
import org.junit.jupiter.api.Test;

/**
 * Proves {@link IActionPlan#execute()} revalidates a frame-scoped target exactly like a page-scoped
 * one: the plan's {@code READY} status only reflects state at {@code plan()} time, and {@code
 * execute()} re-resolves the frame boundary itself - not just the element inside it - fresh against
 * live state, so a frame removed, replaced, or made ambiguous after {@code plan()} is handled
 * correctly (blocked, transparently followed, or blocked, respectively) without ever retaining a
 * stale target from a since-superseded document. Independent proof comes from the fixture's own
 * server-side click counters, split per frame/version so a wrong-target execution would be caught.
 */
class FrameActionPlanIT {

    @Test
    void aPlanTargetingAFrameScopedElementExecutesExactlyOnce() throws Exception {
        try (var support = FramePhase4TestSupport.start();
                var page = support.open("/frames/simple")) {
            IFrame checkout = page.frame().named("checkout").single();
            IElementReference<IElement> target = checkout.find().button().named("Pay").reference();

            IActionPlan<Void> plan = checkout.action().click(target).plan();
            assertThat(plan.status()).isEqualTo(ActionPlanStatus.READY);

            ActionResult<Void> result = plan.execute();

            assertThat(result.success()).isTrue();
            assertThat(support.clickCount("checkout-pay")).isEqualTo(1);
        }
    }

    @Test
    void aPlanWhoseFrameIsRemovedBeforeExecuteNeverTouchesTheBackend() throws Exception {
        try (var support = FramePhase4TestSupport.start();
                var page = support.open("/frames/remove-on-call")) {
            IFrame checkout = page.frame().named("checkout").single();
            IElementReference<IElement> target = checkout.find().button().named("Pay").reference();

            IActionPlan<Void> plan = checkout.action().click(target).plan();
            assertThat(plan.status()).isEqualTo(ActionPlanStatus.READY);

            page.evaluate("removeCheckoutFrame()");
            ActionResult<Void> result = plan.execute();

            assertThat(result.success()).isFalse();
            assertThat(result.executed()).isFalse();
            assertThat(result.failure().orElseThrow().type())
                    .isEqualTo(ActionFailureType.TARGET_NOT_FOUND);
            assertThat(support.clickCount("checkout-pay")).isZero();
        }
    }

    @Test
    void aPlanWhoseFrameIsReplacedBeforeExecuteExecutesAgainstTheNewDocument() throws Exception {
        try (var support = FramePhase4TestSupport.start();
                var page = support.open("/frames/replace-on-call")) {
            IFrame checkout = page.frame().named("checkout").single();
            IElementReference<IElement> target = checkout.find().button().named("Pay").reference();

            IActionPlan<Void> plan = checkout.action().click(target).plan();
            assertThat(plan.status()).isEqualTo(ActionPlanStatus.READY);

            page.evaluate("replaceCheckoutFrame()");
            ActionResult<Void> result = plan.execute();

            assertThat(result.success()).isTrue();
            assertThat(support.clickCount("checkout-v2-pay")).isEqualTo(1);
            assertThat(support.clickCount("checkout-pay")).isZero();
        }
    }

    @Test
    void aPlanWhoseFrameBecomesAmbiguousBeforeExecuteBlocksWithoutClicking() throws Exception {
        try (var support = FramePhase4TestSupport.start();
                var page = support.open("/frames/ambiguous-on-call")) {
            IFrame payment = page.frame().named("payment").single();
            IElementReference<IElement> target = payment.find().button().named("Pay").reference();

            IActionPlan<Void> plan = payment.action().click(target).plan();
            assertThat(plan.status()).isEqualTo(ActionPlanStatus.READY);

            page.evaluate("addSecondPaymentFrame()");
            ActionResult<Void> result = plan.execute();

            assertThat(result.success()).isFalse();
            assertThat(result.executed()).isFalse();
            assertThat(result.failure().orElseThrow().type())
                    .isEqualTo(ActionFailureType.TARGET_AMBIGUOUS);
            assertThat(support.clickCount("payment-1-pay")).isZero();
            assertThat(support.clickCount("payment-2-pay")).isZero();
        }
    }
}
