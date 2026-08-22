package io.webagent4j.plugin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.webagent4j.locator.ILocatorStrategy;
import io.webagent4j.locator.ILocatorStrategyRegistry;
import io.webagent4j.locator.LocatorBackendSearchResult;
import io.webagent4j.locator.LocatorCandidate;
import io.webagent4j.locator.LocatorContext;
import io.webagent4j.locator.LocatorEngine;
import io.webagent4j.locator.LocatorPlanStep;
import io.webagent4j.locator.LocatorStrategyPhase;
import io.webagent4j.locator.LocatorStrategyType;
import io.webagent4j.locator.api.LocatorDefinition;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.NotSerializableException;
import java.io.ObjectOutputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class PluginLoaderTest {

    private static final String SERVICE_TYPE = ILocatorStrategyProvider.class.getName();
    private static final String FAILURE_SENTINEL = "WA4J_PLUGIN_FAILURE_SENTINEL_918273";
    private static final List<String> INSTANTIATION_ORDER = new ArrayList<>();

    @TempDir private Path temporaryDirectory;

    @BeforeEach
    void resetProviders() {
        INSTANTIATION_ORDER.clear();
        CountingProvider.reset();
        MutableListProvider.reset();
    }

    @Test
    void pluginLoad001NoProvidersReturnsEmptyRegistry() throws IOException {
        try (URLClassLoader classLoader = serviceClassLoader()) {
            PluginRegistry registry = new PluginLoader().load(classLoader);

            assertThat(registry.plugins()).isEmpty();
            assertThat(registry.locatorStrategies()).isEmpty();
            assertThat(registry.locatorStrategyRegistry().strategy(LocatorStrategyType.ROLE).type())
                    .isEqualTo(LocatorStrategyType.ROLE);
        }
    }

    @Test
    void pluginLoad002OneValidProviderLoads() throws IOException {
        try (URLClassLoader classLoader = serviceClassLoader(AlphaProvider.class)) {
            PluginRegistry registry = new PluginLoader().load(classLoader);
            ILocatorStrategyRegistry strategies = registry.locatorStrategyRegistry();
            LocatorEngine engine = new LocatorEngine(strategies);

            assertThat(registry.plugins())
                    .containsExactly(pluginDescriptor("alpha-plugin", "1.0.0"));
            assertThat(registry.locatorStrategies())
                    .extracting(ILocatorStrategy::id)
                    .containsExactly("alpha-strategy");
            assertThat(engine).isNotNull();
        }
    }

    @Test
    void pluginLoadExceptionRejectsJavaNativeSerialization() throws IOException {
        PluginLoadException failure = loadFailure(ThrowingDescriptorProvider.class);

        assertThat(failure.failure()).isNotNull();
        assertThatThrownBy(() -> serialize(failure))
                .isInstanceOf(NotSerializableException.class)
                .hasMessageContaining(PluginLoadException.class.getName());
        assertThat(failure.failure()).isNotNull();
    }

    @Test
    void pluginLoad003And004ProvidersInitializeAndPublishInDeterministicOrder() throws IOException {
        try (URLClassLoader reversed = serviceClassLoader(ZetaProvider.class, AlphaProvider.class);
                URLClassLoader forward =
                        serviceClassLoader(AlphaProvider.class, ZetaProvider.class)) {
            PluginRegistry first = new PluginLoader().load(reversed);
            assertThat(INSTANTIATION_ORDER).containsExactly("alpha", "zeta");

            INSTANTIATION_ORDER.clear();
            PluginRegistry second = new PluginLoader().load(forward);

            assertThat(INSTANTIATION_ORDER).containsExactly("alpha", "zeta");
            assertThat(first.plugins()).isEqualTo(second.plugins());
            assertThat(first.plugins())
                    .extracting(descriptor -> descriptor.id().value())
                    .containsExactly("alpha-plugin", "zeta-plugin");
            assertThat(first.locatorStrategies())
                    .extracting(ILocatorStrategy::id)
                    .containsExactly("alpha-strategy", "zeta-strategy");
        }
    }

    @Test
    void pluginLoad005And006DuplicatePluginIdAlwaysFails() throws IOException {
        assertFailure(
                PluginLoadFailureType.DUPLICATE_PLUGIN_ID,
                DuplicatePluginV1Provider.class,
                DuplicatePluginV1AgainProvider.class);
        assertFailure(
                PluginLoadFailureType.DUPLICATE_PLUGIN_ID,
                DuplicatePluginV1Provider.class,
                DuplicatePluginV2Provider.class);
    }

    @Test
    void pluginLoad007ConstructorFailureIsStructuredAndSafe() throws IOException {
        PluginLoadException failure = loadFailure(BrokenConstructorProvider.class);

        assertThat(failure.failure().type())
                .isEqualTo(PluginLoadFailureType.PROVIDER_INSTANTIATION_FAILED);
        assertSafe(failure);
    }

    @Test
    void pluginLoad008And018DescriptorFailureIsStructuredAndSafe() throws IOException {
        PluginLoadException failure = loadFailure(ThrowingDescriptorProvider.class);

        assertThat(failure.failure().type())
                .isEqualTo(PluginLoadFailureType.PROVIDER_DESCRIPTOR_FAILED);
        assertSafe(failure);
    }

    @Test
    void pluginLoad009NullDescriptorIsRejected() throws IOException {
        assertFailure(
                PluginLoadFailureType.INVALID_PLUGIN_DESCRIPTOR, NullDescriptorProvider.class);
    }

    @Test
    void pluginLoad010ThrowingStrategyDiscoveryIsStructuredAndSafe() throws IOException {
        PluginLoadException failure = loadFailure(ThrowingStrategiesProvider.class);

        assertThat(failure.failure().type())
                .isEqualTo(PluginLoadFailureType.PROVIDER_STRATEGY_DISCOVERY_FAILED);
        assertSafe(failure);
    }

    @Test
    void pluginLoad011And012InvalidStrategyListsAreRejected() throws IOException {
        assertFailure(PluginLoadFailureType.INVALID_LOCATOR_STRATEGY, NullStrategiesProvider.class);
        assertFailure(PluginLoadFailureType.INVALID_LOCATOR_STRATEGY, NullElementProvider.class);
    }

    @Test
    void pluginLoad013StandardLocatorStrategyIsRejected() throws IOException {
        assertFailure(
                PluginLoadFailureType.INVALID_LOCATOR_STRATEGY, StandardStrategyProvider.class);
    }

    @Test
    void pluginLoad014And015DuplicateStrategyIdsAreRejected() throws IOException {
        assertFailure(
                PluginLoadFailureType.DUPLICATE_LOCATOR_STRATEGY_ID, DuplicateWithinProvider.class);
        assertFailure(
                PluginLoadFailureType.DUPLICATE_LOCATOR_STRATEGY_ID,
                DuplicateAcrossAlphaProvider.class,
                DuplicateAcrossZetaProvider.class);
    }

    @Test
    void pluginLoad016StandardStrategyIdCollisionIsRejected() throws IOException {
        assertFailure(
                PluginLoadFailureType.DUPLICATE_LOCATOR_STRATEGY_ID,
                StandardIdCollisionProvider.class);
    }

    @Test
    void pluginLoad017MalformedServiceConfigurationIsStructured() throws IOException {
        try (URLClassLoader classLoader = serviceClassLoaderWithNames("missing.provider.Type")) {
            PluginLoadException failure =
                    assertThrows(
                            PluginLoadException.class, () -> new PluginLoader().load(classLoader));

            assertThat(failure.failure().type())
                    .isEqualTo(PluginLoadFailureType.SERVICE_CONFIGURATION_ERROR);
            assertThat(failure.getCause()).isNull();
        }
    }

    @Test
    void pluginLoad019UsesExactlyTheSuppliedClassLoader() throws IOException {
        try (URLClassLoader withProvider = serviceClassLoader(AlphaProvider.class);
                URLClassLoader withoutProvider = serviceClassLoader()) {
            assertThat(new PluginLoader().load(withProvider).plugins()).hasSize(1);
            assertThat(new PluginLoader().load(withoutProvider).plugins()).isEmpty();
            assertThatThrownBy(() -> new PluginLoader().load(null))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    @Test
    void pluginLoad020Through023CallbacksRespectLoadBoundary() throws IOException {
        try (URLClassLoader classLoader = serviceClassLoader(CountingProvider.class)) {
            PluginRegistry registry = new PluginLoader().load(classLoader);

            assertThat(CountingProvider.descriptorCalls).hasValue(1);
            assertThat(CountingProvider.strategiesCalls).hasValue(1);
            assertThat(CountingProvider.supportsCalls).hasValue(0);
            assertThat(CountingProvider.discoverCalls).hasValue(0);
            assertThat(registry.plugins()).hasSize(1);
        }
    }

    @Test
    void pluginLoad024RepeatedLoadsRemainDeterministic() throws IOException {
        try (URLClassLoader classLoader =
                serviceClassLoader(ZetaProvider.class, AlphaProvider.class)) {
            PluginRegistry first = new PluginLoader().load(classLoader);
            PluginRegistry second = new PluginLoader().load(classLoader);

            assertThat(first.plugins()).isEqualTo(second.plugins());
            assertThat(first.locatorStrategies().stream().map(ILocatorStrategy::id).toList())
                    .isEqualTo(
                            second.locatorStrategies().stream().map(ILocatorStrategy::id).toList());
        }
    }

    @Test
    void pluginLoad025SnapshotsProviderCollectionsAndExposesImmutableLists() throws IOException {
        try (URLClassLoader classLoader = serviceClassLoader(MutableListProvider.class)) {
            PluginRegistry registry = new PluginLoader().load(classLoader);
            MutableListProvider.contributions.clear();

            assertThat(registry.locatorStrategies()).hasSize(1);
            assertThatThrownBy(() -> registry.plugins().clear())
                    .isInstanceOf(UnsupportedOperationException.class);
            assertThatThrownBy(() -> registry.locatorStrategies().clear())
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }

    @Test
    void pluginLoad026EmptyStrategyProviderIsRejected() throws IOException {
        assertFailure(PluginLoadFailureType.INVALID_LOCATOR_STRATEGY, EmptyProvider.class);
    }

    @Test
    void rejectsInvalidAndFailingStrategyMetadataWithoutLeakingMessages() throws IOException {
        for (Class<? extends ILocatorStrategyProvider> provider :
                List.of(
                        NullIdProvider.class,
                        BlankIdProvider.class,
                        NullPhaseProvider.class,
                        ThrowingMetadataProvider.class)) {
            PluginLoadException failure = loadFailure(provider);
            assertThat(failure.failure().type())
                    .isEqualTo(PluginLoadFailureType.INVALID_LOCATOR_STRATEGY);
            assertSafe(failure);
        }
    }

    @Test
    void defaultLocatorEngineNeverTriggersVisiblePluginDiscovery() throws IOException {
        ClassLoader original = Thread.currentThread().getContextClassLoader();
        try (URLClassLoader classLoader = serviceClassLoader(CountingProvider.class)) {
            Thread.currentThread().setContextClassLoader(classLoader);

            new LocatorEngine();
            assertThat(CountingProvider.constructorCalls).hasValue(0);

            PluginRegistry registry = new PluginLoader().load();
            assertThat(registry.plugins()).hasSize(1);
            assertThat(CountingProvider.constructorCalls).hasValue(1);
        } finally {
            Thread.currentThread().setContextClassLoader(original);
        }
    }

    @Test
    void missingContextClassLoaderFailsExplicitly() {
        ClassLoader original = Thread.currentThread().getContextClassLoader();
        try {
            Thread.currentThread().setContextClassLoader(null);
            PluginLoadException failure =
                    assertThrows(PluginLoadException.class, () -> new PluginLoader().load());
            assertThat(failure.failure().type())
                    .isEqualTo(PluginLoadFailureType.SERVICE_CONFIGURATION_ERROR);
        } finally {
            Thread.currentThread().setContextClassLoader(original);
        }
    }

    @Test
    void arbitraryErrorsAreNotSwallowed() throws IOException {
        try (URLClassLoader classLoader = serviceClassLoader(ErrorDescriptorProvider.class)) {
            assertThatThrownBy(() -> new PluginLoader().load(classLoader))
                    .isInstanceOf(AssertionError.class)
                    .hasMessageContaining(FAILURE_SENTINEL);
        }
    }

    @SafeVarargs
    private final void assertFailure(
            PluginLoadFailureType expected, Class<? extends ILocatorStrategyProvider>... providers)
            throws IOException {
        assertThat(loadFailure(providers).failure().type()).isEqualTo(expected);
    }

    @SafeVarargs
    private final PluginLoadException loadFailure(
            Class<? extends ILocatorStrategyProvider>... providers) throws IOException {
        try (URLClassLoader classLoader = serviceClassLoader(providers)) {
            return assertThrows(
                    PluginLoadException.class, () -> new PluginLoader().load(classLoader));
        }
    }

    private static void assertSafe(PluginLoadException failure) {
        assertThat(failure.getMessage()).doesNotContain(FAILURE_SENTINEL);
        assertThat(failure.toString()).doesNotContain(FAILURE_SENTINEL);
        assertThat(failure.failure().safeMessage()).doesNotContain(FAILURE_SENTINEL);
        assertThat(failure.getCause()).isNull();
    }

    private static void serialize(Object value) throws IOException {
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                ObjectOutputStream output = new ObjectOutputStream(bytes)) {
            output.writeObject(value);
        }
    }

    @SafeVarargs
    @SuppressWarnings("varargs")
    private final URLClassLoader serviceClassLoader(
            Class<? extends ILocatorStrategyProvider>... providers) throws IOException {
        return serviceClassLoaderWithNames(
                java.util.Arrays.stream(providers).map(Class::getName).toArray(String[]::new));
    }

    private URLClassLoader serviceClassLoaderWithNames(String... providerTypeNames)
            throws IOException {
        Path root = Files.createTempDirectory(temporaryDirectory, "services-");
        Path serviceFile = root.resolve("META-INF/services").resolve(SERVICE_TYPE);
        Files.createDirectories(serviceFile.getParent());
        Files.writeString(
                serviceFile,
                java.util.Arrays.stream(providerTypeNames)
                        .collect(Collectors.joining(System.lineSeparator())),
                StandardCharsets.UTF_8);
        return new URLClassLoader(
                new URL[] {root.toUri().toURL()}, PluginLoaderTest.class.getClassLoader());
    }

    private static PluginDescriptor pluginDescriptor(String id, String version) {
        return new PluginDescriptor(new PluginId(id), new PluginVersion(version));
    }

    private static TestStrategy custom(String id) {
        return new TestStrategy(
                LocatorStrategyType.CUSTOM, id, LocatorStrategyPhase.DETERMINISTIC, 0);
    }

    private static class TestStrategy implements ILocatorStrategy {

        private final LocatorStrategyType type;
        private final String id;
        private final LocatorStrategyPhase phase;
        private final int priority;

        TestStrategy(
                LocatorStrategyType type, String id, LocatorStrategyPhase phase, int priority) {
            this.type = type;
            this.id = id;
            this.phase = phase;
            this.priority = priority;
        }

        @Override
        public LocatorStrategyType type() {
            return type;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public LocatorStrategyPhase phase() {
            return phase;
        }

        @Override
        public int priority() {
            return priority;
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

    public static final class AlphaProvider implements ILocatorStrategyProvider {

        public AlphaProvider() {
            INSTANTIATION_ORDER.add("alpha");
        }

        @Override
        public PluginDescriptor descriptor() {
            return pluginDescriptor("alpha-plugin", "1.0.0");
        }

        @Override
        public List<ILocatorStrategy> strategies() {
            return List.of(custom("alpha-strategy"));
        }
    }

    public static final class ZetaProvider implements ILocatorStrategyProvider {

        public ZetaProvider() {
            INSTANTIATION_ORDER.add("zeta");
        }

        @Override
        public PluginDescriptor descriptor() {
            return pluginDescriptor("zeta-plugin", "1.0.0");
        }

        @Override
        public List<ILocatorStrategy> strategies() {
            return List.of(custom("zeta-strategy"));
        }
    }

    public static final class DuplicatePluginV1Provider implements ILocatorStrategyProvider {
        @Override
        public PluginDescriptor descriptor() {
            return pluginDescriptor("duplicate-plugin", "1.0.0");
        }

        @Override
        public List<ILocatorStrategy> strategies() {
            return List.of(custom("duplicate-v1"));
        }
    }

    public static final class DuplicatePluginV1AgainProvider implements ILocatorStrategyProvider {
        @Override
        public PluginDescriptor descriptor() {
            return pluginDescriptor("duplicate-plugin", "1.0.0");
        }

        @Override
        public List<ILocatorStrategy> strategies() {
            return List.of(custom("duplicate-v1-again"));
        }
    }

    public static final class DuplicatePluginV2Provider implements ILocatorStrategyProvider {
        @Override
        public PluginDescriptor descriptor() {
            return pluginDescriptor("duplicate-plugin", "2.0.0");
        }

        @Override
        public List<ILocatorStrategy> strategies() {
            return List.of(custom("duplicate-v2"));
        }
    }

    public static final class BrokenConstructorProvider implements ILocatorStrategyProvider {
        public BrokenConstructorProvider() {
            throw new IllegalStateException("failure " + FAILURE_SENTINEL);
        }

        @Override
        public PluginDescriptor descriptor() {
            throw new AssertionError("unreachable");
        }

        @Override
        public List<ILocatorStrategy> strategies() {
            throw new AssertionError("unreachable");
        }
    }

    public static final class ThrowingDescriptorProvider implements ILocatorStrategyProvider {
        @Override
        public PluginDescriptor descriptor() {
            throw new IllegalStateException("failure " + FAILURE_SENTINEL);
        }

        @Override
        public List<ILocatorStrategy> strategies() {
            throw new AssertionError("unreachable");
        }
    }

    public static final class NullDescriptorProvider implements ILocatorStrategyProvider {
        @Override
        public PluginDescriptor descriptor() {
            return null;
        }

        @Override
        public List<ILocatorStrategy> strategies() {
            throw new AssertionError("unreachable");
        }
    }

    public static final class ThrowingStrategiesProvider implements ILocatorStrategyProvider {
        @Override
        public PluginDescriptor descriptor() {
            return pluginDescriptor("throwing-strategies", "1.0.0");
        }

        @Override
        public List<ILocatorStrategy> strategies() {
            throw new IllegalStateException("failure " + FAILURE_SENTINEL);
        }
    }

    public static final class NullStrategiesProvider implements ILocatorStrategyProvider {
        @Override
        public PluginDescriptor descriptor() {
            return pluginDescriptor("null-strategies", "1.0.0");
        }

        @Override
        public List<ILocatorStrategy> strategies() {
            return null;
        }
    }

    public static final class NullElementProvider implements ILocatorStrategyProvider {
        @Override
        public PluginDescriptor descriptor() {
            return pluginDescriptor("null-element", "1.0.0");
        }

        @Override
        public List<ILocatorStrategy> strategies() {
            return java.util.Collections.singletonList(null);
        }
    }

    public static final class StandardStrategyProvider implements ILocatorStrategyProvider {
        @Override
        public PluginDescriptor descriptor() {
            return pluginDescriptor("standard-strategy", "1.0.0");
        }

        @Override
        public List<ILocatorStrategy> strategies() {
            return List.of(
                    new TestStrategy(
                            LocatorStrategyType.ROLE,
                            "attempted-role",
                            LocatorStrategyPhase.DETERMINISTIC,
                            0));
        }
    }

    public static final class DuplicateWithinProvider implements ILocatorStrategyProvider {
        @Override
        public PluginDescriptor descriptor() {
            return pluginDescriptor("duplicate-within", "1.0.0");
        }

        @Override
        public List<ILocatorStrategy> strategies() {
            return List.of(custom("same-strategy"), custom("same-strategy"));
        }
    }

    public static final class DuplicateAcrossAlphaProvider implements ILocatorStrategyProvider {
        @Override
        public PluginDescriptor descriptor() {
            return pluginDescriptor("duplicate-across-alpha", "1.0.0");
        }

        @Override
        public List<ILocatorStrategy> strategies() {
            return List.of(custom("cross-plugin-strategy"));
        }
    }

    public static final class DuplicateAcrossZetaProvider implements ILocatorStrategyProvider {
        @Override
        public PluginDescriptor descriptor() {
            return pluginDescriptor("duplicate-across-zeta", "1.0.0");
        }

        @Override
        public List<ILocatorStrategy> strategies() {
            return List.of(custom("cross-plugin-strategy"));
        }
    }

    public static final class StandardIdCollisionProvider implements ILocatorStrategyProvider {
        @Override
        public PluginDescriptor descriptor() {
            return pluginDescriptor("standard-id-collision", "1.0.0");
        }

        @Override
        public List<ILocatorStrategy> strategies() {
            return List.of(custom("ROLE"));
        }
    }

    public static final class CountingProvider implements ILocatorStrategyProvider {

        static final AtomicInteger constructorCalls = new AtomicInteger();
        static final AtomicInteger descriptorCalls = new AtomicInteger();
        static final AtomicInteger strategiesCalls = new AtomicInteger();
        static final AtomicInteger supportsCalls = new AtomicInteger();
        static final AtomicInteger discoverCalls = new AtomicInteger();

        public CountingProvider() {
            constructorCalls.incrementAndGet();
        }

        static void reset() {
            constructorCalls.set(0);
            descriptorCalls.set(0);
            strategiesCalls.set(0);
            supportsCalls.set(0);
            discoverCalls.set(0);
        }

        @Override
        public PluginDescriptor descriptor() {
            descriptorCalls.incrementAndGet();
            return pluginDescriptor("counting-plugin", "1.0.0");
        }

        @Override
        public List<ILocatorStrategy> strategies() {
            strategiesCalls.incrementAndGet();
            return List.of(
                    new TestStrategy(
                            LocatorStrategyType.CUSTOM,
                            "counting-strategy",
                            LocatorStrategyPhase.DETERMINISTIC,
                            0) {
                        @Override
                        public boolean supports(LocatorDefinition definition) {
                            supportsCalls.incrementAndGet();
                            return true;
                        }

                        @Override
                        public LocatorBackendSearchResult discover(
                                LocatorDefinition definition,
                                LocatorPlanStep step,
                                LocatorContext context,
                                Duration timeout,
                                int candidateLimit,
                                List<LocatorCandidate> existingCandidates) {
                            discoverCalls.incrementAndGet();
                            return LocatorBackendSearchResult.complete(List.of());
                        }
                    });
        }
    }

    public static final class MutableListProvider implements ILocatorStrategyProvider {

        static final List<ILocatorStrategy> contributions = new ArrayList<>();

        static void reset() {
            contributions.clear();
            contributions.add(custom("mutable-strategy"));
        }

        @Override
        public PluginDescriptor descriptor() {
            return pluginDescriptor("mutable-list", "1.0.0");
        }

        @Override
        public List<ILocatorStrategy> strategies() {
            return contributions;
        }
    }

    public static final class EmptyProvider implements ILocatorStrategyProvider {
        @Override
        public PluginDescriptor descriptor() {
            return pluginDescriptor("empty-plugin", "1.0.0");
        }

        @Override
        public List<ILocatorStrategy> strategies() {
            return List.of();
        }
    }

    public static final class NullIdProvider implements ILocatorStrategyProvider {
        @Override
        public PluginDescriptor descriptor() {
            return pluginDescriptor("null-id", "1.0.0");
        }

        @Override
        public List<ILocatorStrategy> strategies() {
            return List.of(
                    new TestStrategy(
                            LocatorStrategyType.CUSTOM,
                            null,
                            LocatorStrategyPhase.DETERMINISTIC,
                            0));
        }
    }

    public static final class BlankIdProvider implements ILocatorStrategyProvider {
        @Override
        public PluginDescriptor descriptor() {
            return pluginDescriptor("blank-id", "1.0.0");
        }

        @Override
        public List<ILocatorStrategy> strategies() {
            return List.of(custom(" "));
        }
    }

    public static final class NullPhaseProvider implements ILocatorStrategyProvider {
        @Override
        public PluginDescriptor descriptor() {
            return pluginDescriptor("null-phase", "1.0.0");
        }

        @Override
        public List<ILocatorStrategy> strategies() {
            return List.of(new TestStrategy(LocatorStrategyType.CUSTOM, "null-phase", null, 0));
        }
    }

    public static final class ThrowingMetadataProvider implements ILocatorStrategyProvider {
        @Override
        public PluginDescriptor descriptor() {
            return pluginDescriptor("throwing-metadata", "1.0.0");
        }

        @Override
        public List<ILocatorStrategy> strategies() {
            return List.of(
                    new TestStrategy(
                            LocatorStrategyType.CUSTOM,
                            "unreachable",
                            LocatorStrategyPhase.DETERMINISTIC,
                            0) {
                        @Override
                        public String id() {
                            throw new IllegalStateException("failure " + FAILURE_SENTINEL);
                        }
                    });
        }
    }

    public static final class ErrorDescriptorProvider implements ILocatorStrategyProvider {
        @Override
        public PluginDescriptor descriptor() {
            throw new AssertionError("failure " + FAILURE_SENTINEL);
        }

        @Override
        public List<ILocatorStrategy> strategies() {
            throw new AssertionError("unreachable");
        }
    }
}
