package io.webagent4j.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.webagent4j.browser.IBrowser;
import io.webagent4j.browser.IPage;
import io.webagent4j.core.WebAgent;
import io.webagent4j.locator.api.ElementRole;
import io.webagent4j.observation.Observation;
import java.io.IOException;
import org.junit.jupiter.api.Test;

class BasicObservationIT {

    @Test
    void observesStructuredContentAndReusesThePortableReferenceForAnAction() throws IOException {
        try (ObservationTestApplication application = ObservationTestApplication.start();
                IBrowser browser =
                        WebAgent.browser().playwright().chromium().headless(true).launch()) {
            IPage page = browser.open(application.url("/observation/basic"));

            Observation observation = page.observe();
            Observation second = page.observe();

            assertThat(observation.title()).isEqualTo("Observation fixture");
            assertThat(observation.headings())
                    .extracting("text")
                    .containsExactly("Dashboard", "Activity");
            assertThat(observation.landmarks())
                    .extracting("role")
                    .contains(ElementRole.BANNER, ElementRole.NAVIGATION, ElementRole.MAIN);
            assertThat(observation.navigations())
                    .singleElement()
                    .satisfies(navigation -> assertThat(navigation.currentItem()).isPresent());
            assertThat(observation.tables())
                    .singleElement()
                    .satisfies(
                            table ->
                                    assertThat(table.headers()).containsExactly("Number", "Total"));
            assertThat(observation.lists())
                    .singleElement()
                    .satisfies(
                            list ->
                                    assertThat(list.items())
                                            .containsExactly("Observe", "Act", "Verify"));
            assertThat(observation.images())
                    .singleElement()
                    .satisfies(
                            image -> assertThat(image.accessibleName()).isEqualTo("Company logo"));
            assertThat(observation.dialogs()).hasSize(1);
            assertThat(observation.alerts()).hasSize(1);
            assertThat(observation.tabLists()).hasSize(1);
            assertThat(observation.menus()).hasSize(1);
            assertThat(observation.toCompactText()).contains("BUTTON \"Refresh data\"");
            assertThat(observation.toJson()).contains("\"tables\"").doesNotContain("backendId");
            assertThat(second.fingerprint()).isEqualTo(observation.fingerprint());

            var refresh =
                    observation.buttons().stream()
                            .filter(element -> element.name().equals("Refresh data"))
                            .findFirst()
                            .orElseThrow();
            assertThat(refresh.reference().resolve(page).accessibleName())
                    .isEqualTo("Refresh data");
            assertThat(page.action().click(refresh.reference()).execute().success()).isTrue();
            assertThat(page.evaluate("document.body.dataset.clicked")).isEqualTo("yes");
        }
    }
}
