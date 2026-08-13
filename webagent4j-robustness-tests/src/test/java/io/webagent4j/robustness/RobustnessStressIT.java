package io.webagent4j.robustness;

import static org.assertj.core.api.Assertions.assertThat;

import io.webagent4j.browser.IBrowser;
import io.webagent4j.browser.IPage;
import io.webagent4j.core.WebAgent;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("robustness")
@Tag("slow")
class RobustnessStressIT {

    @Test
    void repeatedObservationsAndResolutionsRemainBoundedAndStable() throws Exception {
        try (RobustnessTestApplication application = RobustnessTestApplication.start();
                IBrowser browser =
                        WebAgent.browser().playwright().chromium().headless(true).launch();
                IPage page = browser.open(application.fixtureUrl("clean/semantic-controls.html"))) {
            var expectedFingerprint = page.observe().fingerprint();
            for (int iteration = 0; iteration < 101; iteration++) {
                assertThat(page.observe().fingerprint()).isEqualTo(expectedFingerprint);
                assertThat(
                                page.find()
                                        .button()
                                        .named("Save profile")
                                        .single()
                                        .attributes()
                                        .get("data-target"))
                        .isEqualTo("clean-save");
            }
        }
    }

    @Test
    void browserSupportsRepeatedPageLifecycleOperations() throws Exception {
        try (RobustnessTestApplication application = RobustnessTestApplication.start();
                IBrowser browser =
                        WebAgent.browser().playwright().chromium().headless(true).launch()) {
            for (int iteration = 0; iteration < 5; iteration++) {
                try (IPage page =
                        browser.open(application.fixtureUrl("clean/semantic-controls.html"))) {
                    assertThat(page.find().heading().named("Account settings").single())
                            .isNotNull();
                }
            }
        }
    }
}
