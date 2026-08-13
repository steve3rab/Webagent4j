package io.webagent4j.integration;

import static io.webagent4j.verification.Verifications.textVisible;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class DelayedVerificationIT {

    @Test
    void pollsUntilTheDelayedResultAppears() throws Exception {
        try (var support = Phase4TestSupport.start();
                var page = support.open("/actions/delayed-result")) {
            var button = page.find().button().named("Process once").single();
            var result =
                    page.action().click(button).expect(textVisible("Completed once")).execute();
            assertThat(result.success()).isTrue();
            assertThat(support.clickCount()).isEqualTo(1);
        }
    }
}
