package io.webagent4j.plugin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.webagent4j.locator.ILocatorStrategy;
import io.webagent4j.locator.LocatorBackendSearchResult;
import io.webagent4j.locator.LocatorCandidate;
import io.webagent4j.locator.LocatorContext;
import io.webagent4j.locator.LocatorPlanStep;
import io.webagent4j.locator.LocatorStrategyRegistry;
import io.webagent4j.locator.LocatorStrategyType;
import io.webagent4j.locator.api.LocatorDefinition;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class PluginRegistryTest {

    @Test
    void snapshotsCollectionsAndProvidesExactLookup() {
        PluginDescriptor descriptor = descriptor("example-plugin");
        List<PluginDescriptor> descriptors = new ArrayList<>(List.of(descriptor));
        List<ILocatorStrategy> custom = new ArrayList<>(List.of(new EmptyStrategy("custom-id")));
        LocatorStrategyRegistry merged = merged(custom);

        PluginRegistry registry = new PluginRegistry(descriptors, custom, merged);
        descriptors.clear();
        custom.clear();

        assertThat(registry.plugins()).containsExactly(descriptor);
        assertThat(registry.find(new PluginId("example-plugin"))).contains(descriptor);
        assertThat(registry.find(new PluginId("missing-plugin"))).isEmpty();
        assertThat(registry.locatorStrategies())
                .extracting(ILocatorStrategy::id)
                .containsExactly("custom-id");
        assertThat(registry.locatorStrategyRegistry().strategy(LocatorStrategyType.ROLE).type())
                .isEqualTo(LocatorStrategyType.ROLE);
    }

    @Test
    void outwardCollectionsAreImmutableAndToStringAvoidsStrategyCallbacks() {
        PluginDescriptor descriptor = descriptor("example-plugin");
        ILocatorStrategy strategy = new PoisonToStringStrategy();
        PluginRegistry registry =
                new PluginRegistry(
                        List.of(descriptor), List.of(strategy), merged(List.of(strategy)));

        assertThatThrownBy(() -> registry.plugins().clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> registry.locatorStrategies().clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(registry.toString())
                .contains("example-plugin", "locatorStrategyCount=1")
                .doesNotContain("strategy-secret");
        assertThatThrownBy(() -> registry.find(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void structuredExceptionRetainsOnlyTheSafeFailure() {
        PluginLoadFailure failure =
                new PluginLoadFailure(
                        PluginLoadFailureType.INVALID_PLUGIN_DESCRIPTOR,
                        java.util.Optional.empty(),
                        java.util.Optional.of("example.Provider"),
                        "The descriptor is invalid.");
        PluginLoadException exception = new PluginLoadException(failure);

        assertThat(exception.failure()).isSameAs(failure);
        assertThat(exception.getMessage()).isEqualTo("The descriptor is invalid.");
        assertThat(exception.getCause()).isNull();
    }

    private static PluginDescriptor descriptor(String id) {
        return new PluginDescriptor(new PluginId(id), new PluginVersion("1.0.0"));
    }

    private static LocatorStrategyRegistry merged(List<ILocatorStrategy> custom) {
        List<ILocatorStrategy> strategies =
                new ArrayList<>(LocatorStrategyRegistry.defaults().strategies());
        strategies.addAll(custom);
        return new LocatorStrategyRegistry(strategies);
    }

    private static class EmptyStrategy implements ILocatorStrategy {

        private final String id;

        EmptyStrategy(String id) {
            this.id = id;
        }

        @Override
        public LocatorStrategyType type() {
            return LocatorStrategyType.CUSTOM;
        }

        @Override
        public String id() {
            return id;
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
            return LocatorBackendSearchResult.complete(List.of());
        }
    }

    private static final class PoisonToStringStrategy extends EmptyStrategy {

        private PoisonToStringStrategy() {
            super("poison-to-string");
        }

        @Override
        public String toString() {
            throw new IllegalStateException("strategy-secret");
        }
    }
}
