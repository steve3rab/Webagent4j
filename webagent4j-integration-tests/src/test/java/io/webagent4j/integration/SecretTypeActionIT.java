package io.webagent4j.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.webagent4j.action.Secret;
import io.webagent4j.observation.ObservationOptions;
import org.junit.jupiter.api.Test;

class SecretTypeActionIT {

    @Test
    void neverRendersTheTypedSecretInStructuredArtifacts() throws Exception {
        String sensitive = "WEBAGENT4J_PHASE4_SECRET_VALUE";
        try (var support = Phase4TestSupport.start();
                var page = support.open("/actions/type")) {
            var password = page.find().textbox().labelled("Password").single();

            var result = page.action().typeSecret(password, Secret.of(sensitive)).execute();
            String artifacts =
                    result
                            + result.events().toString()
                            + result.diagnostics()
                            + page.observe(
                                            ObservationOptions.builder()
                                                    .includeInputValues(true)
                                                    .build())
                                    .toJson();

            assertThat(result.success()).isTrue();
            assertThat(artifacts).doesNotContain(sensitive);
        }
    }

    @Test
    void neverRendersTheSequentiallyTypedSecretInStructuredArtifacts() throws Exception {
        // Governed Actions V2: typeSequentially's secret variant gets the same diagnostic-safety
        // guarantee as fill's, proven independently rather than assumed from the fill case above.
        String sensitive = "WEBAGENT4J_PHASE4_SEQUENTIAL_SECRET_VALUE";
        try (var support = Phase4TestSupport.start();
                var page = support.open("/actions/type")) {
            var password = page.find().textbox().labelled("Password").single();

            var result =
                    page.action().typeSequentiallySecret(password, Secret.of(sensitive)).execute();
            String artifacts =
                    result
                            + result.events().toString()
                            + result.diagnostics()
                            + page.observe(
                                            ObservationOptions.builder()
                                                    .includeInputValues(true)
                                                    .build())
                                    .toJson();

            assertThat(result.success()).isTrue();
            assertThat(artifacts).doesNotContain(sensitive);
        }
    }
}
