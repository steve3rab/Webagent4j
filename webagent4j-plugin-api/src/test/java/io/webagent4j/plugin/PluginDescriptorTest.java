package io.webagent4j.plugin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class PluginDescriptorTest {

    @Test
    void hasSmallImmutableValueSemantics() {
        PluginDescriptor descriptor =
                new PluginDescriptor(new PluginId("example-plugin"), new PluginVersion("1.0.0"));

        assertThat(descriptor)
                .isEqualTo(
                        new PluginDescriptor(
                                new PluginId("example-plugin"), new PluginVersion("1.0.0")));
        assertThat(descriptor.id().value()).isEqualTo("example-plugin");
        assertThat(descriptor.version().value()).isEqualTo("1.0.0");
    }

    @Test
    void rejectsMissingFields() {
        PluginId id = new PluginId("example-plugin");
        PluginVersion version = new PluginVersion("1.0.0");

        assertThatThrownBy(() -> new PluginDescriptor(null, version))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new PluginDescriptor(id, null))
                .isInstanceOf(NullPointerException.class);
    }
}
