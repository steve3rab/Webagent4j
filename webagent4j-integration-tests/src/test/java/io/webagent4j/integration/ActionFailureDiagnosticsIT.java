package io.webagent4j.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.webagent4j.action.ObservationCapturePolicy;
import org.junit.jupiter.api.Test;

class ActionFailureDiagnosticsIT {

    @Test
    void recordsSafeFailureDiagnosticsAndTimings() throws Exception {
        try (var support = Phase4TestSupport.start();
                var page = support.open("/actions/failure")) {
            var result =
                    page.action()
                            .click(page.find().button().named("Disabled action").single())
                            .captureObservations(ObservationCapturePolicy.ON_FAILURE)
                            .execute();
            assertThat(result.actionId()).isNotNull();
            assertThat(result.failure()).isPresent();
            assertThat(result.preconditions()).isNotEmpty();
            assertThat(result.duration().isNegative()).isFalse();
            assertThat(result.afterObservation()).isNotNull();
            assertThat(result.diagnostics().targetDescription()).contains("Disabled action");
        }
    }
}
