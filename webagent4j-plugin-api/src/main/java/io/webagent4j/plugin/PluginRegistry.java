package io.webagent4j.plugin;

import io.webagent4j.locator.ILocatorStrategy;
import io.webagent4j.locator.LocatorStrategyRegistry;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Immutable result of one explicit plugin load. */
public final class PluginRegistry {

    private final List<PluginDescriptor> plugins;
    private final Map<PluginId, PluginDescriptor> byId;
    private final List<ILocatorStrategy> locatorStrategies;
    private final LocatorStrategyRegistry locatorStrategyRegistry;

    PluginRegistry(
            List<PluginDescriptor> plugins,
            List<ILocatorStrategy> locatorStrategies,
            LocatorStrategyRegistry locatorStrategyRegistry) {
        this.plugins = List.copyOf(Objects.requireNonNull(plugins, "plugins"));
        this.locatorStrategies =
                List.copyOf(Objects.requireNonNull(locatorStrategies, "locatorStrategies"));
        this.locatorStrategyRegistry =
                Objects.requireNonNull(locatorStrategyRegistry, "locatorStrategyRegistry");
        Map<PluginId, PluginDescriptor> index = new LinkedHashMap<>();
        this.plugins.forEach(descriptor -> index.put(descriptor.id(), descriptor));
        byId = Map.copyOf(index);
    }

    /** Returns plugin descriptors sorted by plugin ID. */
    public List<PluginDescriptor> plugins() {
        return plugins;
    }

    /** Finds a descriptor by its exact plugin ID. */
    public Optional<PluginDescriptor> find(PluginId id) {
        return Optional.ofNullable(byId.get(Objects.requireNonNull(id, "id")));
    }

    /** Returns custom strategies in locator execution order. */
    public List<ILocatorStrategy> locatorStrategies() {
        return locatorStrategies;
    }

    /** Returns standard strategies augmented with the discovered custom strategies. */
    public LocatorStrategyRegistry locatorStrategyRegistry() {
        return locatorStrategyRegistry;
    }

    @Override
    public String toString() {
        return "PluginRegistry[plugins="
                + plugins
                + ", locatorStrategyCount="
                + locatorStrategies.size()
                + "]";
    }
}
