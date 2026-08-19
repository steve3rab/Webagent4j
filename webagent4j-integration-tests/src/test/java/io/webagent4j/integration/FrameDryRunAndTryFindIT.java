package io.webagent4j.integration;

import static io.webagent4j.verification.Verifications.textVisible;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import io.webagent4j.browser.IFrame;
import io.webagent4j.locator.AmbiguousLocatorException;
import org.junit.jupiter.api.Test;

/**
 * Proves {@code dryRun()} and {@code tryFind()} behave identically inside a frame as they already
 * do at the page level: a dry run resolves the frame, the target, and preconditions, but never
 * actually navigates, clicks, or types - proven by the fixture's own zero-side-effect click counter
 * - and {@code tryFind()} only ever converts a genuine typed "not found" into {@code
 * Optional.empty()}, whether that not-found is the target inside an existing frame or the frame
 * itself, while frame ambiguity still raises the normal explicit exception rather than being
 * swallowed. A frame-scoped action's postcondition is also verified against that frame's own
 * document, not the main page.
 */
class FrameDryRunAndTryFindIT {

    @Test
    void aDryRunInsideAFrameResolvesEverythingButCausesNoSideEffect() throws Exception {
        try (var support = FramePhase4TestSupport.start();
                var page = support.open("/frames/simple")) {
            IFrame checkout = page.frame().named("checkout").single();

            var result =
                    checkout.action()
                            .click(checkout.find().button().named("Pay").reference())
                            .dryRun()
                            .execute();

            assertThat(result.success()).isTrue();
            assertThat(result.dryRun()).isTrue();
            assertThat(result.executed()).isFalse();
            assertThat(support.clickCount("checkout-pay")).isZero();
        }
    }

    @Test
    void tryFindReturnsEmptyForATargetTrulyAbsentInsideAnExistingFrame() throws Exception {
        try (var support = FramePhase4TestSupport.start();
                var page = support.open("/frames/no-target")) {
            IFrame checkout = page.frame().named("checkout").single();

            assertThat(checkout.find().button().named("Pay").tryFind()).isEmpty();
        }
    }

    @Test
    void tryFindReturnsEmptyForAFrameThatIsItselfAbsent() throws Exception {
        try (var support = FramePhase4TestSupport.start();
                var page = support.open("/frames/no-iframe")) {
            assertThat(page.frame().named("checkout").tryFind()).isEmpty();
        }
    }

    @Test
    void tryFindNeverSwallowsFrameAmbiguityIntoAnEmptyResult() throws Exception {
        try (var support = FramePhase4TestSupport.start();
                var page = support.open("/frames/two-identical-payment")) {
            assertThatExceptionOfType(AmbiguousLocatorException.class)
                    .isThrownBy(() -> page.frame().named("payment").tryFind());
        }
    }

    @Test
    void aFrameScopedActionsPostconditionIsVerifiedAgainstTheFramesOwnDocument() throws Exception {
        try (var support = FramePhase4TestSupport.start();
                var page = support.open("/frames/simple")) {
            IFrame checkout = page.frame().named("checkout").single();

            checkout.action()
                    .click(checkout.find().button().named("Pay").reference())
                    .expect(textVisible("Done"))
                    .execute()
                    .throwIfFailed();

            assertThat(support.clickCount("checkout-pay")).isEqualTo(1);
        }
    }
}
