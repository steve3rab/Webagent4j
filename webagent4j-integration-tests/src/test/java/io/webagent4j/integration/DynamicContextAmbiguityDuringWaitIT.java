package io.webagent4j.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import io.webagent4j.action.ActionFailureType;
import io.webagent4j.action.ActionResult;
import io.webagent4j.browser.InteractionContext;
import io.webagent4j.locator.AmbiguousLocatorException;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * Proves a structured semantic scope is re-resolved against the live DOM on every individual
 * polling attempt of an active wait, not once before the wait begins - and that context ambiguity
 * is a fail-safe condition checked independently of target ambiguity, exactly like target ambiguity
 * already is.
 *
 * <p>Both fixtures start with exactly one unique "Shipping" region and a matching "Continue" button
 * inside it; a duplicate "Shipping" region is injected 150ms after the page loads, while a {@code
 * stableFor(...)} wait started at t=0 is still actively polling through that moment. The
 * ambiguity-with-unique-target fixture is the stronger proof: the duplicate region carries no
 * "Continue" button at all, so a page-wide "Continue" search alone would still find exactly one
 * match throughout - only a locator that genuinely re-resolves and re-validates the structured
 * scope on every poll can catch this. Independent proof comes from server-side click counters that
 * must stay at zero either way.
 */
class DynamicContextAmbiguityDuringWaitIT {

    @Test
    void aContextThatBecomesAmbiguousWhileActivelyWaitingForTargetStabilityFailsImmediately()
            throws Exception {
        try (var support = Phase4TestSupport.start();
                var page = support.open("/actions/context-dynamic-ambiguous")) {
            assertThatExceptionOfType(AmbiguousLocatorException.class)
                    .isThrownBy(
                            () ->
                                    page.find(
                                                    InteractionContext.context()
                                                            .containingText("Shipping"))
                                            .button()
                                            .named("Continue")
                                            .stableFor(Duration.ofMillis(300))
                                            .single());

            assertThat(support.clickCount("shipping-1")).isZero();
            assertThat(support.clickCount("shipping-2")).isZero();
        }
    }

    @Test
    void aContextThatBecomesAmbiguousMidWaitFailsEvenWhenTheTargetItselfStaysUniquePageWide()
            throws Exception {
        try (var support = Phase4TestSupport.start();
                var page = support.open("/actions/context-dynamic-ambiguous-target-unique")) {
            assertThatExceptionOfType(AmbiguousLocatorException.class)
                    .isThrownBy(
                            () ->
                                    page.find(
                                                    InteractionContext.context()
                                                            .containingText("Shipping"))
                                            .button()
                                            .named("Continue")
                                            .stableFor(Duration.ofMillis(300))
                                            .single());

            assertThat(support.clickCount("shipping-1")).isZero();
        }
    }

    /**
     * Proves the fix applies through the action pipeline's own target re-resolution, not only a
     * direct {@code .single()} call: {@code ActionTargetResolver} resolves the reference's own
     * {@code stableFor(...)} wait, which is itself the wait actively polling through the moment the
     * duplicate "Shipping" region appears. Ambiguity is never retried, so the action fails on its
     * first resolution attempt with {@code TARGET_AMBIGUOUS}, and the backend click is never
     * invoked - proven independently by the fixture's own server-side click counter staying at
     * zero.
     */
    @Test
    void anActionWhoseReferenceContextBecomesAmbiguousMidWaitNeverInvokesTheBackend()
            throws Exception {
        try (var support = Phase4TestSupport.start();
                var page = support.open("/actions/context-dynamic-ambiguous-target-unique")) {
            var target =
                    page.find(InteractionContext.context().containingText("Shipping"))
                            .button()
                            .named("Continue")
                            .stableFor(Duration.ofMillis(300))
                            .reference();

            ActionResult<Void> result =
                    page.action().click(target).timeout(Duration.ofSeconds(2)).execute();

            assertThat(result.success()).isFalse();
            assertThat(result.executed()).isFalse();
            assertThat(result.failure().orElseThrow().type())
                    .isEqualTo(ActionFailureType.TARGET_AMBIGUOUS);
            assertThat(support.clickCount("shipping-1")).isZero();
        }
    }
}
