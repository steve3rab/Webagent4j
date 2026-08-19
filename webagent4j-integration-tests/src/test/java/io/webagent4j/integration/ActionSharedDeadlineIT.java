package io.webagent4j.integration;

import static io.webagent4j.verification.Verifications.textVisible;
import static org.assertj.core.api.Assertions.assertThat;

import io.webagent4j.action.ActionStatus;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * Proves an action's postconditions share one deadline instead of each independently receiving a
 * full, fresh timeout - the bug this mission's shared {@code WaitBudget} migration closes. Two
 * postconditions that never become true, under one 300ms timeout, must together take on the order
 * of 300ms of real wall-clock time, not two independent 300ms waits stacked one after another.
 */
class ActionSharedDeadlineIT {

    @Test
    void twoUnmetPostconditionsTogetherStayWithinOneActionTimeout() throws Exception {
        try (var support = Phase4TestSupport.start();
                var page = support.open("/actions/click")) {
            var button = page.find().button().named("Increment").single();
            Duration timeout = Duration.ofMillis(300);

            Instant started = Instant.now();
            var result =
                    page.action()
                            .click(button)
                            .expect(textVisible("Never appears (first)"))
                            .expect(textVisible("Never appears (second)"))
                            .timeout(timeout)
                            .execute();
            Duration elapsed = Duration.between(started, Instant.now());

            assertThat(result.status()).isEqualTo(ActionStatus.TIMEOUT);
            // A per-condition budget bug would let this approach 2 * timeout (600ms): each
            // postcondition independently exhausting its own full allowance. Generous slack for
            // scheduling noise, but nowhere near double.
            assertThat(elapsed).isLessThan(timeout.multipliedBy(2));
            assertThat(page.title()).isEqualTo("Click actions");
        }
    }
}
