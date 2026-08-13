package io.webagent4j.integration;

import static io.webagent4j.verification.Verifications.textVisible;
import static org.assertj.core.api.Assertions.assertThat;

import io.webagent4j.common.RetryPolicy;
import org.junit.jupiter.api.Test;

class NonIdempotentActionRetryIT {

    @Test
    void neverRepeatsClickWhilePollingItsPostcondition() throws Exception {
        try (var support = Phase4TestSupport.start();
                var page = support.open("/actions/retry")) {
            var button = page.find().button().named("Process once").single();
            page.action()
                    .click(button)
                    .retry(RetryPolicy.defaults())
                    .expect(textVisible("Completed once"))
                    .execute()
                    .throwIfFailed();
            assertThat(support.clickCount()).isEqualTo(1);
        }
    }
}
