package io.webagent4j.integration;

import static io.webagent4j.verification.Verifications.textVisible;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class DynamicTargetActionIT {

    @Test
    void reResolvesACompletelyReplacedSemanticTarget() throws Exception {
        try (var support = Phase4TestSupport.start();
                var page = support.open("/actions/dynamic-target")) {
            var target = page.find().button().named("Confirm").reference();
            page.action().waitFor(Duration.ofMillis(300)).execute().throwIfFailed();

            var result = page.action().click(target).expect(textVisible("Confirmed")).execute();

            assertThat(result.success()).isTrue();
        }
    }
}
