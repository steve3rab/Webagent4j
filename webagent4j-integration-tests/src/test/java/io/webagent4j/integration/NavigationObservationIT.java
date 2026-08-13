package io.webagent4j.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.webagent4j.browser.IBrowser;
import io.webagent4j.core.WebAgent;
import io.webagent4j.observation.NavigationOrientation;
import java.io.IOException;
import org.junit.jupiter.api.Test;

class NavigationObservationIT {

    @Test
    void observesNavigationOwnershipAndCurrentPage() throws IOException {
        try (ObservationTestApplication application = ObservationTestApplication.start();
                IBrowser browser =
                        WebAgent.browser().playwright().chromium().headless(true).launch()) {
            var observation = browser.open(application.url("/observation/navigation")).observe();

            assertThat(observation.navigations())
                    .singleElement()
                    .satisfies(
                            navigation -> {
                                assertThat(navigation.name()).isEqualTo("Primary");
                                assertThat(navigation.links())
                                        .extracting("name")
                                        .containsExactly("Overview");
                                assertThat(navigation.currentItem()).isPresent();
                                assertThat(navigation.orientation())
                                        .isEqualTo(NavigationOrientation.HORIZONTAL);
                            });
        }
    }
}
