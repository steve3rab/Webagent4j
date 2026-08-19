package io.webagent4j.integration;

import static io.webagent4j.verification.Verifications.textVisible;
import static org.assertj.core.api.Assertions.assertThat;

import io.webagent4j.action.ActionStatus;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class ActionTimeoutIT {

    @Test
    void boundsAnUnmetPostconditionAndKeepsThePageUsable() throws Exception {
        try (var support = Phase4TestSupport.start();
                var page = support.open("/actions/click-timeout-oracle")) {
            var button = page.find().button().named("Increment").single();
            var result =
                    page.action()
                            .click(button)
                            .expect(textVisible("Never appears"))
                            .timeout(Duration.ofMillis(300))
                            .execute();
            assertThat(result.status()).isEqualTo(ActionStatus.TIMEOUT);
            assertThat(page.title()).isEqualTo("Click timeout oracle");

            // Independent proof the click backend action ran exactly once - postcondition polling
            // never re-executes it, timeout or not - from two separate oracles. The DOM counter is
            // already settled by the time execute() returns: the onclick handler updates it
            // synchronously, before the async fetch() in the same handler is even issued.
            assertThat(page.find().id("counter").single().text()).isEqualTo("1");
            // The server-side counter reflects the same handler's fetch(), which is asynchronous;
            // poll it briefly and deterministically instead of assuming it already landed.
            awaitClickCount(support, 1);
            assertThat(support.clickCount()).isEqualTo(1);
        }
    }

    /** Polls {@code support}'s server-side click counter until it reaches {@code expected}. */
    private static void awaitClickCount(Phase4TestSupport support, int expected)
            throws InterruptedException {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(2));
        while (support.clickCount() < expected && Instant.now().isBefore(deadline)) {
            Thread.sleep(20);
        }
    }
}
