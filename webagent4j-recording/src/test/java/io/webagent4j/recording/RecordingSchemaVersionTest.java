package io.webagent4j.recording;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class RecordingSchemaVersionTest {

    @Test
    void v1NumberIsOne() {
        assertThat(RecordingSchemaVersion.V1.number()).isEqualTo(1);
    }

    @Test
    void fromNumberResolvesKnownVersion() {
        assertThat(RecordingSchemaVersion.fromNumber(1)).isEqualTo(RecordingSchemaVersion.V1);
    }

    @Test
    void fromNumberRejectsUnknownVersionWithoutEchoingIt() {
        assertThatThrownBy(() -> RecordingSchemaVersion.fromNumber(999))
                .isInstanceOf(RecordingFormatException.class)
                .hasMessageNotContaining("999");
    }
}
