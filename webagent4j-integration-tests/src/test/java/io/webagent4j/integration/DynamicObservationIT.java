package io.webagent4j.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.webagent4j.browser.IBrowser;
import io.webagent4j.core.WebAgent;
import java.io.IOException;
import org.junit.jupiter.api.Test;

class DynamicObservationIT {

    @Test
    void diffsFreshSnapshotsWithoutReadingLiveStateFromTheOldObservation() throws IOException {
        try (ObservationTestApplication application = ObservationTestApplication.start();
                IBrowser browser =
                        WebAgent.browser().playwright().chromium().headless(true).launch()) {
            var page = browser.open(application.url("/observation/dynamic"));
            var before = page.observe();

            page.evaluate("mutateObservationPage()");
            var after = page.observe();
            var diff = before.diff(after);

            assertThat(before.title()).isEqualTo("Dynamic observation");
            assertThat(after.title()).isEqualTo("Notifications loaded");
            assertThat(diff.titleChanged()).isTrue();
            assertThat(diff.elementsAdded())
                    .extracting("role")
                    .contains(io.webagent4j.locator.api.ElementRole.STATUS);
            assertThat(diff.elementsChanged())
                    .anySatisfy(
                            change -> {
                                assertThat(change.before().role())
                                        .isEqualTo(io.webagent4j.locator.api.ElementRole.BUTTON);
                                assertThat(change.changedProperties())
                                        .contains(io.webagent4j.observation.ChangedProperty.TEXT);
                            });
            assertThat(before.fingerprint()).isNotEqualTo(after.fingerprint());
        }
    }
}
