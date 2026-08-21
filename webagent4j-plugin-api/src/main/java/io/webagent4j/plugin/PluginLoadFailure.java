package io.webagent4j.plugin;

import java.util.Objects;
import java.util.Optional;

/**
 * Safe structured diagnostic for plugin loading.
 *
 * <p>Plugin and provider identities are non-sensitive diagnostic metadata. The message is owned by
 * WebAgent4J and never contains a raw provider exception message.
 */
public record PluginLoadFailure(
        PluginLoadFailureType type,
        Optional<PluginId> pluginId,
        Optional<String> providerTypeName,
        String safeMessage) {

    /** Validates the safe diagnostic fields. */
    public PluginLoadFailure {
        Objects.requireNonNull(type, "type");
        pluginId = Objects.requireNonNull(pluginId, "pluginId");
        providerTypeName = Objects.requireNonNull(providerTypeName, "providerTypeName");
        Objects.requireNonNull(safeMessage, "safeMessage");
        if (providerTypeName.stream().anyMatch(String::isBlank) || safeMessage.isBlank()) {
            throw new IllegalArgumentException("plugin load diagnostic fields must not be blank");
        }
    }
}
