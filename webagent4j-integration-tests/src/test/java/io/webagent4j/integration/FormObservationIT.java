package io.webagent4j.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.webagent4j.browser.IBrowser;
import io.webagent4j.core.WebAgent;
import io.webagent4j.observation.InputFieldType;
import io.webagent4j.observation.Observation;
import io.webagent4j.observation.ObservationOptions;
import java.io.IOException;
import org.junit.jupiter.api.Test;

class FormObservationIT {

    @Test
    void retainsOptInOrdinaryValuesButNeverRetainsPasswordOrTokenValues() throws IOException {
        try (ObservationTestApplication application = ObservationTestApplication.start();
                IBrowser browser =
                        WebAgent.browser().playwright().chromium().headless(true).launch()) {
            var page = browser.open(application.url("/observation/forms"));

            Observation defaults = page.observe();
            Observation values =
                    page.observe(
                            ObservationOptions.builder()
                                    .includeInputValues(true)
                                    .maxSelectOptions(2)
                                    .build());

            assertThat(defaults.toString()).doesNotContain("user@example.test");
            assertThat(values.forms())
                    .singleElement()
                    .satisfies(
                            form -> {
                                assertThat(form.fields())
                                        .filteredOn(field -> field.type() == InputFieldType.EMAIL)
                                        .extracting(field -> field.value().value().orElseThrow())
                                        .containsExactly("user@example.test");
                                assertThat(form.fields())
                                        .filteredOn(
                                                field -> field.type() == InputFieldType.PASSWORD)
                                        .allSatisfy(
                                                field ->
                                                        assertThat(field.value().redacted())
                                                                .isTrue());
                                assertThat(form.fields())
                                        .filteredOn(field -> field.name().equals("API token"))
                                        .allSatisfy(
                                                field ->
                                                        assertThat(field.value().redacted())
                                                                .isTrue());
                                assertThat(form.fields())
                                        .filteredOn(field -> field.type() == InputFieldType.SELECT)
                                        .allSatisfy(
                                                field -> {
                                                    assertThat(field.options())
                                                            .containsExactly("France", "Germany");
                                                    assertThat(field.optionsTruncated()).isTrue();
                                                });
                            });
            assertThat(values.toString())
                    .doesNotContain("WEBAGENT4J_SECRET_TEST_VALUE", "literal-token-secret");
            assertThat(values.toJson())
                    .doesNotContain("WEBAGENT4J_SECRET_TEST_VALUE", "literal-token-secret");
            assertThat(values.toCompactText())
                    .doesNotContain("WEBAGENT4J_SECRET_TEST_VALUE", "literal-token-secret");
            assertThat(defaults.diff(values).toString())
                    .doesNotContain("WEBAGENT4J_SECRET_TEST_VALUE", "literal-token-secret");
        }
    }
}
