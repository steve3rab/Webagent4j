package io.webagent4j.integration;

import static io.webagent4j.verification.Verifications.textVisible;
import static org.assertj.core.api.Assertions.assertThat;

import io.webagent4j.action.ObservationCapturePolicy;
import org.junit.jupiter.api.Test;

class DialogActionIT {

    @Test
    void observesANewlyOpenedSemanticDialog() throws Exception {
        try (var support = Phase4TestSupport.start();
                var page = support.open("/actions/dialog")) {
            var button = page.find().button().named("Open notifications").single();
            var result =
                    page.action()
                            .click(button)
                            .expect(textVisible("Notifications"))
                            .captureObservations(ObservationCapturePolicy.ALWAYS)
                            .execute();
            assertThat(result.success()).isTrue();
            assertThat(result.diff().dialogsOpened()).isNotEmpty();
        }
    }
}
