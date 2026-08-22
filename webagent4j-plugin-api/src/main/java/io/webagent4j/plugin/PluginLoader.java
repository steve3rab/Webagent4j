package io.webagent4j.plugin;

import io.webagent4j.locator.ILocatorStrategy;
import io.webagent4j.locator.LocatorBackendSearchResult;
import io.webagent4j.locator.LocatorCandidate;
import io.webagent4j.locator.LocatorContext;
import io.webagent4j.locator.LocatorPlanStep;
import io.webagent4j.locator.LocatorStrategyPhase;
import io.webagent4j.locator.LocatorStrategyRegistry;
import io.webagent4j.locator.LocatorStrategyType;
import io.webagent4j.locator.api.LocatorDefinition;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.Set;

/**
 * Explicit deterministic loader for trusted locator strategy providers.
 *
 * <p>The loader is stateless: every call performs an independent synchronous load on the caller's
 * thread and retains no providers or class loaders afterward. The caller retains ownership of an
 * explicitly supplied class loader.
 */
public final class PluginLoader {

    /** Creates an independent loader with no global cache or mutable process state. */
    public PluginLoader() {}

    /**
     * Loads providers visible to the current thread context class loader.
     *
     * @return a complete immutable registry, which may be empty
     * @throws PluginLoadException when the context class loader is unavailable or loading fails
     */
    public PluginRegistry load() {
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        if (classLoader == null) {
            throw failure(
                    PluginLoadFailureType.SERVICE_CONFIGURATION_ERROR,
                    null,
                    null,
                    "The thread context class loader is not available.");
        }
        return load(classLoader);
    }

    /**
     * Loads providers using exactly the supplied class loader without taking ownership of it.
     * Callers explicitly opt into running trusted provider construction and callbacks in-process.
     *
     * @param classLoader class loader used by {@link ServiceLoader}
     * @return a complete immutable registry, which may be empty
     * @throws NullPointerException when the class loader is null
     * @throws PluginLoadException when any provider or contribution is invalid
     */
    public PluginRegistry load(ClassLoader classLoader) {
        Objects.requireNonNull(classLoader, "classLoader");
        List<ProviderEntry> providerEntries = providerEntries(classLoader);
        List<PluginDescriptor> descriptors = new ArrayList<>();
        List<ILocatorStrategy> strategies = new ArrayList<>();
        Set<PluginId> pluginIds = new HashSet<>();
        Set<String> strategyIds = standardStrategyIds();

        for (ProviderEntry entry : providerEntries) {
            ILocatorStrategyProvider provider = instantiate(entry);
            PluginDescriptor descriptor = descriptor(provider, entry.typeName());
            if (!pluginIds.add(descriptor.id())) {
                throw failure(
                        PluginLoadFailureType.DUPLICATE_PLUGIN_ID,
                        descriptor.id(),
                        entry.typeName(),
                        "Multiple providers declared the same plugin ID.");
            }
            List<ILocatorStrategy> contributions =
                    strategies(provider, descriptor.id(), entry.typeName());
            if (contributions.isEmpty()) {
                throw failure(
                        PluginLoadFailureType.INVALID_LOCATOR_STRATEGY,
                        descriptor.id(),
                        entry.typeName(),
                        "A locator strategy provider must contribute at least one strategy.");
            }
            for (ILocatorStrategy contribution : contributions) {
                RegisteredLocatorStrategy validated =
                        validate(contribution, descriptor.id(), entry.typeName());
                if (!strategyIds.add(validated.id())) {
                    throw failure(
                            PluginLoadFailureType.DUPLICATE_LOCATOR_STRATEGY_ID,
                            descriptor.id(),
                            entry.typeName(),
                            "A locator strategy ID is already registered.");
                }
                strategies.add(validated);
            }
            descriptors.add(descriptor);
        }

        descriptors.sort(Comparator.comparing(descriptor -> descriptor.id().value()));
        LocatorStrategyRegistry mergedRegistry = mergedRegistry(strategies);
        List<ILocatorStrategy> orderedCustomStrategies =
                mergedRegistry.strategies().stream()
                        .filter(strategy -> strategy.type() == LocatorStrategyType.CUSTOM)
                        .toList();
        return new PluginRegistry(descriptors, orderedCustomStrategies, mergedRegistry);
    }

    private static List<ProviderEntry> providerEntries(ClassLoader classLoader) {
        try {
            return ServiceLoader.load(ILocatorStrategyProvider.class, classLoader).stream()
                    .map(provider -> new ProviderEntry(provider.type().getName(), provider))
                    .sorted(Comparator.comparing(ProviderEntry::typeName))
                    .toList();
        } catch (ServiceConfigurationError error) {
            throw failure(
                    PluginLoadFailureType.SERVICE_CONFIGURATION_ERROR,
                    null,
                    null,
                    "A plugin service declaration is invalid.");
        }
    }

    private static ILocatorStrategyProvider instantiate(ProviderEntry entry) {
        try {
            return entry.provider().get();
        } catch (ServiceConfigurationError | RuntimeException error) {
            throw failure(
                    PluginLoadFailureType.PROVIDER_INSTANTIATION_FAILED,
                    null,
                    entry.typeName(),
                    "A locator strategy provider could not be instantiated.");
        }
    }

    private static PluginDescriptor descriptor(
            ILocatorStrategyProvider provider, String providerTypeName) {
        PluginDescriptor descriptor;
        try {
            descriptor = provider.descriptor();
        } catch (RuntimeException error) {
            throw failure(
                    PluginLoadFailureType.PROVIDER_DESCRIPTOR_FAILED,
                    null,
                    providerTypeName,
                    "A locator strategy provider failed to describe its plugin.");
        }
        if (descriptor == null) {
            throw failure(
                    PluginLoadFailureType.INVALID_PLUGIN_DESCRIPTOR,
                    null,
                    providerTypeName,
                    "A locator strategy provider returned an invalid descriptor.");
        }
        return descriptor;
    }

    private static List<ILocatorStrategy> strategies(
            ILocatorStrategyProvider provider, PluginId pluginId, String providerTypeName) {
        List<ILocatorStrategy> strategies;
        try {
            strategies = provider.strategies();
        } catch (RuntimeException error) {
            throw failure(
                    PluginLoadFailureType.PROVIDER_STRATEGY_DISCOVERY_FAILED,
                    pluginId,
                    providerTypeName,
                    "A locator strategy provider failed to declare its strategies.");
        }
        if (strategies == null) {
            throw failure(
                    PluginLoadFailureType.INVALID_LOCATOR_STRATEGY,
                    pluginId,
                    providerTypeName,
                    "A locator strategy provider returned an invalid strategy list.");
        }
        try {
            return List.copyOf(strategies);
        } catch (RuntimeException error) {
            throw failure(
                    PluginLoadFailureType.INVALID_LOCATOR_STRATEGY,
                    pluginId,
                    providerTypeName,
                    "A locator strategy provider returned an invalid strategy list.");
        }
    }

    private static RegisteredLocatorStrategy validate(
            ILocatorStrategy strategy, PluginId pluginId, String providerTypeName) {
        try {
            LocatorStrategyType type = strategy.type();
            String id = strategy.id();
            LocatorStrategyPhase phase = strategy.phase();
            int priority = strategy.priority();
            if (type != LocatorStrategyType.CUSTOM || id == null || id.isBlank() || phase == null) {
                throw failure(
                        PluginLoadFailureType.INVALID_LOCATOR_STRATEGY,
                        pluginId,
                        providerTypeName,
                        "A plugin contributed an invalid custom locator strategy.");
            }
            return new RegisteredLocatorStrategy(strategy, id, phase, priority);
        } catch (PluginLoadException error) {
            throw error;
        } catch (RuntimeException error) {
            throw failure(
                    PluginLoadFailureType.INVALID_LOCATOR_STRATEGY,
                    pluginId,
                    providerTypeName,
                    "A plugin contributed an invalid custom locator strategy.");
        }
    }

    private static Set<String> standardStrategyIds() {
        Set<String> identifiers = new HashSet<>();
        LocatorStrategyRegistry.defaults().strategies().stream()
                .map(ILocatorStrategy::id)
                .forEach(identifiers::add);
        return identifiers;
    }

    private static LocatorStrategyRegistry mergedRegistry(List<ILocatorStrategy> customStrategies) {
        List<ILocatorStrategy> merged =
                new ArrayList<>(LocatorStrategyRegistry.defaults().strategies());
        merged.addAll(customStrategies);
        try {
            return new LocatorStrategyRegistry(merged);
        } catch (IllegalArgumentException error) {
            throw failure(
                    PluginLoadFailureType.INVALID_LOCATOR_STRATEGY,
                    null,
                    null,
                    "The merged locator strategy registry is invalid.");
        }
    }

    private static PluginLoadException failure(
            PluginLoadFailureType type,
            PluginId pluginId,
            String providerTypeName,
            String safeMessage) {
        return new PluginLoadException(
                new PluginLoadFailure(
                        type,
                        Optional.ofNullable(pluginId),
                        Optional.ofNullable(providerTypeName),
                        safeMessage));
    }

    private record ProviderEntry(
            String typeName, ServiceLoader.Provider<ILocatorStrategyProvider> provider) {}

    private record RegisteredLocatorStrategy(
            ILocatorStrategy delegate, String id, LocatorStrategyPhase phase, int priority)
            implements ILocatorStrategy {

        private RegisteredLocatorStrategy {
            Objects.requireNonNull(delegate, "delegate");
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(phase, "phase");
        }

        @Override
        public LocatorStrategyType type() {
            return LocatorStrategyType.CUSTOM;
        }

        @Override
        public boolean supports(LocatorDefinition definition) {
            return delegate.supports(definition);
        }

        @Override
        @SuppressWarnings("checkstyle:ParameterNumber")
        public LocatorBackendSearchResult discover(
                LocatorDefinition definition,
                LocatorPlanStep step,
                LocatorContext context,
                Duration timeout,
                int candidateLimit,
                List<LocatorCandidate> existingCandidates) {
            return delegate.discover(
                    definition, step, context, timeout, candidateLimit, existingCandidates);
        }
    }
}
