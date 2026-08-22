package io.webagent4j.plugin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class PluginVersionTest {

    @Test
    void acceptsOpaqueBoundedVersionLabelsWithValueSemantics() {
        assertThat(new PluginVersion("1.0.0")).isEqualTo(new PluginVersion("1.0.0"));
        assertThat(new PluginVersion("1.2.3-SNAPSHOT").toString()).isEqualTo("1.2.3-SNAPSHOT");
        assertThat(new PluginVersion("2026.08").value()).isEqualTo("2026.08");
    }

    @Test
    void rejectsNullWhitespaceControlsAndOversizedLabels() {
        assertThatThrownBy(() -> new PluginVersion(null)).isInstanceOf(NullPointerException.class);
        for (String value :
                new String[] {"", " ", "1 0", " 1.0", "1.0 ", "1\n0", "1/0", "v".repeat(65)}) {
            assertThatThrownBy(() -> new PluginVersion(value))
                    .as("invalid plugin version %s", value)
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
