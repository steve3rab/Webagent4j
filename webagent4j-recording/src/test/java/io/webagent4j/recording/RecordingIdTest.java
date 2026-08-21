package io.webagent4j.recording;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class RecordingIdTest {

    @Test
    void rejectsNullValue() {
        assertThatThrownBy(() -> new RecordingId(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void rejectsBlankValue() {
        assertThatThrownBy(() -> new RecordingId("   "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void toStringReturnsRawValue() {
        assertThat(new RecordingId("run-42").toString()).isEqualTo("run-42");
    }

    @Test
    void equalsAndHashCodeAreValueBased() {
        assertThat(new RecordingId("run-42")).isEqualTo(new RecordingId("run-42"));
        assertThat(new RecordingId("run-42")).isNotEqualTo(new RecordingId("run-43"));
    }
}
