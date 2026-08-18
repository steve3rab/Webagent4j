package io.webagent4j.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import io.webagent4j.browser.InteractionContext;
import io.webagent4j.dom.IElement;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * Proves a structured semantic scope that is wholesale replaced by a new DOM node carrying the same
 * semantics, while an active wait is still polling, resolves against the live replacement instead
 * of failing or resolving a stale, now-detached node. A {@code stableFor(...)} wait started at t=0
 * is still actively polling when the original "Shipping" region (and its "Continue" button) is
 * removed and replaced by a fresh region 150ms later; the replacement resets the target's stability
 * window (a genuinely different DOM node is a different identity), so resolution only succeeds once
 * the fresh node has itself remained stable for the configured duration - proving the wait is
 * really tracking live DOM state throughout, not a value captured on an earlier poll.
 */
class DynamicContextReplacementDuringWaitIT {

    @Test
    void aContextReplacedWhileActivelyWaitingResolvesAgainstTheLiveReplacementNode()
            throws Exception {
        try (var support = Phase4TestSupport.start();
                var page = support.open("/actions/context-dynamic-replaced")) {
            AtomicReference<IElement> resolved = new AtomicReference<>();

            assertThatCode(
                            () ->
                                    resolved.set(
                                            page.find(
                                                            InteractionContext.context()
                                                                    .containingText("Shipping"))
                                                    .button()
                                                    .named("Continue")
                                                    .stableFor(Duration.ofMillis(300))
                                                    .timeout(Duration.ofMillis(1500))
                                                    .single()))
                    .doesNotThrowAnyException();

            assertThat(resolved.get().accessibleName()).isEqualTo("Continue");
        }
    }
}
