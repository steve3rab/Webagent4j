package io.webagent4j.integration;

import static io.webagent4j.verification.Verifications.textVisible;
import static org.assertj.core.api.Assertions.assertThat;

import io.webagent4j.browser.IFrame;
import org.junit.jupiter.api.Test;

/**
 * Proves cross-origin {@code <iframe>} traversal works end to end using two independent local HTTP
 * servers on different loopback ports - no public internet dependency, and no weakened browser
 * security (Playwright's default cross-origin isolation stays fully in effect). The frame is
 * resolved and acted on through exactly the same public {@code IFrame} contract as a same-origin
 * frame; the click is verified against the cross-origin server's own independent counter.
 */
class FrameCrossOriginIT {

    @Test
    void resolvesAndActsOnATargetInsideACrossOriginFrame() throws Exception {
        try (var support = FramePhase4TestSupport.startWithCrossOrigin();
                var page = support.open("/frames/cross-origin-host")) {
            IFrame external = page.frame().named("external").single();

            external.action()
                    .click(external.find().button().named("Pay").reference())
                    .expect(textVisible("Done"))
                    .execute()
                    .throwIfFailed();

            assertThat(support.crossOriginClickCount("cross-origin-pay")).isEqualTo(1);
        }
    }
}
