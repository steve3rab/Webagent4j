package io.webagent4j.integration;

import static io.webagent4j.verification.Verifications.valueEquals;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TypeActionIT {

    @Test
    void replacesAndVerifiesTheLiveInputValue() throws Exception {
        try (var support = Phase4TestSupport.start();
                var page = support.open("/actions/type")) {
            var email = page.find().textbox().labelled("Email").single();

            var result =
                    page.action()
                            .type(email, "user@example.test")
                            .expect(valueEquals("user@example.test"))
                            .execute();

            assertThat(result.success()).isTrue();
            assertThat(email.value()).isEqualTo("user@example.test");
        }
    }
}
