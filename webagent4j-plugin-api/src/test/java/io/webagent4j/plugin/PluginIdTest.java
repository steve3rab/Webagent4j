package io.webagent4j.plugin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class PluginIdTest {

    @Test
    void acceptsStableLowercaseIdentifiersWithValueSemantics() {
        PluginId id = new PluginId("com.example.semantic-locator");

        assertThat(id).isEqualTo(new PluginId("com.example.semantic-locator"));
        assertThat(id.hashCode())
                .isEqualTo(new PluginId("com.example.semantic-locator").hashCode());
        assertThat(id.toString()).isEqualTo("com.example.semantic-locator");
        assertThat(new PluginId("internal_custom-labels").value())
                .isEqualTo("internal_custom-labels");
    }

    @Test
    void rejectsNullBlankNonCanonicalAndOversizedIdentifiers() {
        assertThatThrownBy(() -> new PluginId(null)).isInstanceOf(NullPointerException.class);
        for (String value :
                new String[] {
                    "",
                    " ",
                    "UPPER",
                    " leading",
                    "trailing ",
                    "two words",
                    "line\nbreak",
                    "-leading",
                    "trailing-",
                    "two..parts",
                    "a".repeat(129)
                }) {
            assertThatThrownBy(() -> new PluginId(value))
                    .as("invalid plugin id %s", value)
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
