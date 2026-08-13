package io.webagent4j.integration;

import static io.webagent4j.verification.Verifications.textVisible;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class BrowserRecoveryAfterActionFailureIT {

    @Test
    void keepsThePageUsableAfterAnExpectedFailure() throws Exception {
        try (var support = Phase4TestSupport.start();
                var page = support.open("/actions/failure")) {
            var failed =
                    page.action()
                            .click(page.find().button().named("Disabled action").single())
                            .execute();
            assertThat(failed.success()).isFalse();
            page.action().navigate(support.url("/actions/click")).execute().throwIfFailed();
            page.action()
                    .click(page.find().button().named("Increment").single())
                    .expect(textVisible("1"))
                    .execute()
                    .throwIfFailed();
        }
    }
}
