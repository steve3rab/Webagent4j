package io.webagent4j.observation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.webagent4j.locator.api.ElementRole;
import io.webagent4j.observation.spi.SnapshotElement;
import java.util.List;
import org.junit.jupiter.api.Test;

class ObservationRedactionPolicyTest {

    private final SecureObservationRedactionPolicy policy = new SecureObservationRedactionPolicy();

    @Test
    void alwaysRedactsPasswordsTokensAndPaymentFields() {
        List<SnapshotElement> sensitive =
                List.of(
                        ObservationSnapshotFixtures.builder(
                                        "password",
                                        null,
                                        0,
                                        ElementRole.TEXTBOX,
                                        "input",
                                        "Password")
                                .field(InputFieldType.PASSWORD)
                                .sensitive(true)
                                .valuePresent(true)
                                .build(),
                        ObservationSnapshotFixtures.builder(
                                        "token", null, 1, ElementRole.TEXTBOX, "input", "API token")
                                .field(InputFieldType.TEXT)
                                .value("do-not-retain")
                                .build(),
                        ObservationSnapshotFixtures.builder(
                                        "card",
                                        null,
                                        2,
                                        ElementRole.TEXTBOX,
                                        "input",
                                        "Card number")
                                .field(InputFieldType.TEXT)
                                .attribute("autocomplete", "cc-number")
                                .value("do-not-retain")
                                .build());

        sensitive.forEach(
                element -> {
                    assertThat(policy.isSensitive(element)).isTrue();
                    assertThat(
                                    policy.redact(
                                            element,
                                            ObservationOptions.builder()
                                                    .includeInputValues(true)
                                                    .build()))
                            .matches(ObservedValue::redacted)
                            .extracting(ObservedValue::value)
                            .isEqualTo(java.util.Optional.empty());
                });
    }

    @Test
    void ordinaryValuesAreOptInAndSnapshotRejectsRawSensitiveValues() {
        SnapshotElement email =
                ObservationSnapshotFixtures.builder(
                                "email", null, 0, ElementRole.TEXTBOX, "input", "Email")
                        .field(InputFieldType.EMAIL)
                        .value("user@example.test")
                        .build();

        assertThat(policy.redact(email, ObservationOptions.defaults()).disposition())
                .isEqualTo(ValueDisposition.OMITTED);
        assertThat(
                        policy.redact(
                                email,
                                ObservationOptions.builder().includeInputValues(true).build()))
                .extracting(value -> value.value().orElseThrow())
                .isEqualTo("user@example.test");
        assertThatThrownBy(
                        () ->
                                ObservationSnapshotFixtures.builder(
                                                "unsafe",
                                                null,
                                                1,
                                                ElementRole.TEXTBOX,
                                                "input",
                                                "Password")
                                        .field(InputFieldType.PASSWORD)
                                        .sensitive(true)
                                        .value("must-not-enter-snapshot")
                                        .build())
                .isInstanceOf(IllegalArgumentException.class);
    }
}
