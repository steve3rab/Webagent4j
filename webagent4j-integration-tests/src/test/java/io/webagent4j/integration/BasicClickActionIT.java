package io.webagent4j.integration;

import static io.webagent4j.verification.Verifications.textVisible;
import static org.assertj.core.api.Assertions.assertThat;

import io.webagent4j.action.ObservationCapturePolicy;
import org.junit.jupiter.api.Test;

class BasicClickActionIT {

    @Test
    void clicksSemanticallyAndCapturesTheObservedChange() throws Exception {
        try (var support = Phase4TestSupport.start();
                var page = support.open("/actions/click")) {
            var target = page.find().button().named("Increment").reference();
            var result =
                    page.action()
                            .click(target)
                            .expect(textVisible("1"))
                            .captureObservations(ObservationCapturePolicy.ALWAYS)
                            .execute();

            assertThat(result.success()).isTrue();
            assertThat(result.beforeObservation()).isNotNull();
            assertThat(result.afterObservation()).isNotNull();
            assertThat(result.diff()).isNotNull();
            assertThat(result.events()).extracting("actionId").containsOnly(result.actionId());
        }
    }
}
