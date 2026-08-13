package io.webagent4j.integration;

import static io.webagent4j.verification.Verifications.textVisible;
import static org.assertj.core.api.Assertions.assertThat;

import io.webagent4j.action.ActionStatus;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class ActionTimeoutIT {

    @Test
    void boundsAnUnmetPostconditionAndKeepsThePageUsable() throws Exception {
        try (var support = Phase4TestSupport.start();
                var page = support.open("/actions/click")) {
            var button = page.find().button().named("Increment").single();
            var result =
                    page.action()
                            .click(button)
                            .expect(textVisible("Never appears"))
                            .timeout(Duration.ofMillis(300))
                            .execute();
            assertThat(result.status()).isEqualTo(ActionStatus.TIMEOUT);
            assertThat(page.title()).isEqualTo("Click actions");
        }
    }
}
