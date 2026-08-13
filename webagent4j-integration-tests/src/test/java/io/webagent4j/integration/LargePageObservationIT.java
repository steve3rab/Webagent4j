package io.webagent4j.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.webagent4j.browser.IBrowser;
import io.webagent4j.core.WebAgent;
import io.webagent4j.observation.ObservationOptions;
import io.webagent4j.observation.ObservationTruncationType;
import java.io.IOException;
import org.junit.jupiter.api.Test;

class LargePageObservationIT {

    @Test
    void boundsLargePagesAndReportsElementTruncation() throws IOException {
        try (ObservationTestApplication application = ObservationTestApplication.start();
                IBrowser browser =
                        WebAgent.browser().playwright().chromium().headless(true).launch()) {
            var page = browser.open(application.url("/observation/large"));

            var observation = page.observe(ObservationOptions.builder().maxElements(25).build());

            assertThat(observation.elements()).hasSize(25);
            assertThat(observation.statistics().elementsVisited()).isGreaterThan(900);
            assertThat(observation.statistics().truncations())
                    .extracting("type")
                    .contains(ObservationTruncationType.ELEMENTS);
        }
    }
}
