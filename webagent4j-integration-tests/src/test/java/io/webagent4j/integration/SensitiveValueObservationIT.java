package io.webagent4j.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.webagent4j.browser.IBrowser;
import io.webagent4j.core.WebAgent;
import io.webagent4j.observation.ObservationOptions;
import java.io.IOException;
import org.junit.jupiter.api.Test;

class SensitiveValueObservationIT {

    private static final String SECRET = "WEBAGENT4J_SECRET_TEST_VALUE";

    @Test
    void neverExposesTheKnownSecretAcrossThePublicObservationSurface() throws IOException {
        try (ObservationTestApplication application = ObservationTestApplication.start();
                IBrowser browser =
                        WebAgent.browser().playwright().chromium().headless(true).launch()) {
            var page = browser.open(application.url("/observation/sensitive"));
            var before =
                    page.observe(ObservationOptions.builder().includeInputValues(true).build());
            page.evaluate("document.getElementById('email').value = 'changed@example.test'");
            var after = page.observe(ObservationOptions.builder().includeInputValues(true).build());

            assertThat(before.toString()).doesNotContain(SECRET);
            assertThat(before.toJson()).doesNotContain(SECRET);
            assertThat(before.toCompactText()).doesNotContain(SECRET);
            assertThat(before.diff(after).toString()).doesNotContain(SECRET);
            assertThat(before.warnings())
                    .allSatisfy(warning -> assertThat(warning.toString()).doesNotContain(SECRET));
            assertThat(before.statistics().toString()).doesNotContain(SECRET);
        }
    }
}
