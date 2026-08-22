package io.webagent4j.integration.plugin;

import io.webagent4j.locator.ILocatorStrategy;
import io.webagent4j.locator.LocatorBackendQuery;
import io.webagent4j.locator.LocatorBackendSearchResult;
import io.webagent4j.locator.LocatorCandidate;
import io.webagent4j.locator.LocatorContext;
import io.webagent4j.locator.LocatorPlanStep;
import io.webagent4j.locator.LocatorStrategyPhase;
import io.webagent4j.locator.LocatorStrategyType;
import io.webagent4j.locator.api.LocatorDefinition;
import io.webagent4j.plugin.ILocatorStrategyProvider;
import io.webagent4j.plugin.PluginDescriptor;
import io.webagent4j.plugin.PluginId;
import io.webagent4j.plugin.PluginVersion;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

/** Test-only provider used by the real Playwright plugin integration test. */
public final class TestLocatorStrategyProvider implements ILocatorStrategyProvider {

    private static final AtomicInteger CONSTRUCTOR_CALLS = new AtomicInteger();
    private static final AtomicInteger SUPPORTS_CALLS = new AtomicInteger();
    private static final AtomicInteger DISCOVER_CALLS = new AtomicInteger();

    /** Records explicit provider construction. */
    public TestLocatorStrategyProvider() {
        CONSTRUCTOR_CALLS.incrementAndGet();
    }

    /** Resets test counters before an integration scenario. */
    public static void reset() {
        CONSTRUCTOR_CALLS.set(0);
        SUPPORTS_CALLS.set(0);
        DISCOVER_CALLS.set(0);
    }

    /** Returns provider construction count. */
    public static int constructorCalls() {
        return CONSTRUCTOR_CALLS.get();
    }

    /** Returns strategy support callback count. */
    public static int supportsCalls() {
        return SUPPORTS_CALLS.get();
    }

    /** Returns strategy discovery callback count. */
    public static int discoverCalls() {
        return DISCOVER_CALLS.get();
    }

    @Override
    public PluginDescriptor descriptor() {
        return new PluginDescriptor(
                new PluginId("test-playwright-locator"), new PluginVersion("1.0.0"));
    }

    @Override
    public List<ILocatorStrategy> strategies() {
        return List.of(new TestDataStrategy());
    }

    private static final class TestDataStrategy implements ILocatorStrategy {

        @Override
        public LocatorStrategyType type() {
            return LocatorStrategyType.CUSTOM;
        }

        @Override
        public String id() {
            return "test-plugin-data-strategy";
        }

        @Override
        public LocatorStrategyPhase phase() {
            return LocatorStrategyPhase.DETERMINISTIC;
        }

        @Override
        public int priority() {
            return 100;
        }

        @Override
        public boolean supports(LocatorDefinition definition) {
            SUPPORTS_CALLS.incrementAndGet();
            return definition.accessibleName().stream()
                    .anyMatch(match -> match.value().equals("Plugin route"));
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
            DISCOVER_CALLS.incrementAndGet();
            LocatorBackendQuery query =
                    new LocatorBackendQuery(
                            LocatorStrategyType.TEST_ID,
                            definition.role(),
                            Optional.empty(),
                            Optional.empty(),
                            Optional.of("plugin-target"));
            return context.backend()
                    .find(query, context.scope(), context.config(), timeout, candidateLimit);
        }
    }
}
