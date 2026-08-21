package io.webagent4j.plugin;

import java.util.Objects;

/** Small immutable description of a plugin using non-sensitive metadata. */
public record PluginDescriptor(PluginId id, PluginVersion version) {

    /** Validates required descriptor fields. */
    public PluginDescriptor {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(version, "version");
    }
}
