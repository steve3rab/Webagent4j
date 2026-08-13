package io.webagent4j.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.webagent4j.action.ObservationCapturePolicy;
import org.junit.jupiter.api.Test;

class ActionObservationCaptureIT {

    @Test
    void exposesBeforeAfterDiffAndDifferentFingerprints() throws Exception {
        try (var support = Phase4TestSupport.start();
                var page = support.open("/actions/click")) {
            var result =
                    page.action()
                            .click(page.find().button().named("Increment").single())
                            .captureObservations(ObservationCapturePolicy.ALWAYS)
                            .execute();
            assertThat(result.beforeObservation().fingerprint())
                    .isNotEqualTo(result.afterObservation().fingerprint());
            assertThat(result.diff().empty()).isFalse();
        }
    }
}
