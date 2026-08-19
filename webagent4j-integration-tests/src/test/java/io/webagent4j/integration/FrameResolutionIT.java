package io.webagent4j.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import io.webagent4j.browser.IFrame;
import io.webagent4j.dom.IElement;
import io.webagent4j.locator.LocatorNotFoundException;
import org.junit.jupiter.api.Test;

/**
 * Proves the baseline public frame-traversal contract: a target inside one {@code <iframe>} is
 * reachable through {@code page.frame().named(...).single()} followed by an ordinary {@code
 * frame.find()} chain - the exact same 0/1/N element resolution semantics used for the main
 * document - and that an absent target inside an existing frame, or an absent frame itself, both
 * fail as typed {@link LocatorNotFoundException}, never as a silent empty result or a generic
 * exception.
 */
class FrameResolutionIT {

    @Test
    void locatesATargetInsideOneIframeByAStablePublicCriterion() throws Exception {
        try (var support = FramePhase4TestSupport.start();
                var page = support.open("/frames/simple")) {
            IFrame checkout = page.frame().named("checkout").single();

            IElement pay = checkout.find().button().named("Pay").single();

            assertThat(pay.accessibleName()).isEqualTo("Pay");
        }
    }

    @Test
    void anIdCriterionAlsoResolvesTheSameFrameDeterministically() throws Exception {
        try (var support = FramePhase4TestSupport.start();
                var page = support.open("/frames/simple")) {
            IFrame checkout = page.frame().withId("checkout").single();

            assertThat(checkout.url()).contains("/frames/child/checkout");
        }
    }

    @Test
    void aTargetAbsentInsideAnExistingFrameFailsAsTypedNotFound() throws Exception {
        try (var support = FramePhase4TestSupport.start();
                var page = support.open("/frames/no-target")) {
            IFrame checkout = page.frame().named("checkout").single();

            assertThatExceptionOfType(LocatorNotFoundException.class)
                    .isThrownBy(() -> checkout.find().button().named("Pay").single());
        }
    }

    @Test
    void anAbsentIframeItselfFailsAsTypedNotFoundBeforeAnyTargetLookup() throws Exception {
        try (var support = FramePhase4TestSupport.start();
                var page = support.open("/frames/no-iframe")) {
            assertThatExceptionOfType(LocatorNotFoundException.class)
                    .isThrownBy(() -> page.frame().named("checkout").single());
        }
    }
}
