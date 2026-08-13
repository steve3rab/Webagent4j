package io.webagent4j.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.webagent4j.browser.IBrowser;
import io.webagent4j.core.WebAgent;
import io.webagent4j.observation.ObservationOptions;
import io.webagent4j.observation.ObservationWarningType;
import java.io.IOException;
import org.junit.jupiter.api.Test;

class ObservationAccessibilityEdgeCaseIT {

    @Test
    void reportsFactualAccessibilityWarningsAndControlsHiddenInclusion() throws IOException {
        try (ObservationTestApplication application = ObservationTestApplication.start();
                IBrowser browser =
                        WebAgent.browser().playwright().chromium().headless(true).launch()) {
            var page = browser.open(application.url("/observation/accessibility"));

            var visible = page.observe();
            var withHidden = page.observe(ObservationOptions.builder().includeHidden(true).build());

            assertThat(visible.warnings())
                    .extracting("type")
                    .contains(
                            ObservationWarningType.HEADING_LEVEL_JUMP,
                            ObservationWarningType.BUTTON_WITHOUT_NAME,
                            ObservationWarningType.FORM_CONTROL_WITHOUT_LABEL);
            assertThat(visible.buttons())
                    .noneMatch(element -> element.name().equals("Hidden action"));
            assertThat(withHidden.buttons())
                    .anyMatch(element -> element.name().equals("Hidden action"));
            assertThat(visible.images()).isEmpty();
            assertThat(visible.alerts())
                    .singleElement()
                    .satisfies(alert -> assertThat(alert.text()).isEqualTo("Ready"));
        }
    }
}
