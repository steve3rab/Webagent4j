package io.webagent4j.robustness;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

class RobustnessBaselineTest {

    @Test
    void versionedExpectationsMatchTheCompleteCorpus() throws IOException {
        Map<String, Object> baseline;
        try (InputStream input =
                getClass().getClassLoader().getResourceAsStream("robustness-baseline.json")) {
            assertThat(input).isNotNull();
            baseline =
                    new ObjectMapper()
                            .readValue(input, new TypeReference<Map<String, Object>>() {});
        }
        @SuppressWarnings("unchecked")
        Map<String, String> expectations =
                new TreeMap<>((Map<String, String>) baseline.get("expectations"));
        Map<String, String> corpus = new TreeMap<>();
        RobustnessCorpus.scenarios()
                .forEach(scenario -> corpus.put(scenario.id(), scenario.expectation().name()));

        assertThat(baseline.get("schemaVersion")).isEqualTo(1);
        assertThat(expectations).hasSize(100).isEqualTo(corpus);
    }
}
