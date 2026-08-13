package io.webagent4j.locator;

import io.webagent4j.locator.api.LocatorDefinition;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Immutable locator strategy registry assembled at engine bootstrap. */
public final class LocatorStrategyRegistry implements ILocatorStrategyRegistry {

    private static final Comparator<ILocatorStrategy> CUSTOM_ORDER =
            Comparator.comparing(ILocatorStrategy::phase)
                    .thenComparing(Comparator.comparingInt(ILocatorStrategy::priority).reversed())
                    .thenComparing(ILocatorStrategy::id);

    private final List<ILocatorStrategy> strategies;
    private final Map<LocatorStrategyType, ILocatorStrategy> byType;

    /** Creates a registry and rejects duplicate standard types or strategy identifiers. */
    public LocatorStrategyRegistry(List<ILocatorStrategy> strategies) {
        Objects.requireNonNull(strategies, "strategies");
        List<ILocatorStrategy> standard = new ArrayList<>();
        List<ILocatorStrategy> custom = new ArrayList<>();
        Map<LocatorStrategyType, ILocatorStrategy> index = new EnumMap<>(LocatorStrategyType.class);
        Set<String> identifiers = new HashSet<>();
        for (ILocatorStrategy strategy : strategies) {
            Objects.requireNonNull(strategy, "strategy");
            if (!identifiers.add(strategy.id())) {
                throw new IllegalArgumentException("duplicate strategy id: " + strategy.id());
            }
            if (strategy.type() == LocatorStrategyType.CUSTOM) {
                custom.add(strategy);
            } else {
                ILocatorStrategy previous = index.put(strategy.type(), strategy);
                if (previous != null) {
                    throw new IllegalArgumentException("duplicate strategy: " + strategy.type());
                }
                standard.add(strategy);
            }
        }
        custom.sort(CUSTOM_ORDER);
        standard.addAll(custom);
        this.strategies = List.copyOf(standard);
        byType = Map.copyOf(index);
    }

    /** Creates the complete standard backend-delegating strategy registry. */
    public static LocatorStrategyRegistry defaults() {
        return new LocatorStrategyRegistry(
                java.util.Arrays.stream(LocatorStrategyType.values())
                        .filter(type -> type != LocatorStrategyType.CUSTOM)
                        .map(BackendStrategy::new)
                        .map(ILocatorStrategy.class::cast)
                        .toList());
    }

    @Override
    public List<ILocatorStrategy> strategies() {
        return strategies;
    }

    @Override
    public ILocatorStrategy strategy(LocatorStrategyType type) {
        ILocatorStrategy strategy = byType.get(Objects.requireNonNull(type, "type"));
        if (strategy == null) {
            throw new IllegalArgumentException(
                    "no standard locator strategy registered for " + type);
        }
        return strategy;
    }

    private record BackendStrategy(LocatorStrategyType type) implements ILocatorStrategy {
        private BackendStrategy {
            Objects.requireNonNull(type, "type");
        }

        @Override
        public String id() {
            return type.name();
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
            return context.backend()
                    .find(step.query(), context.scope(), context.config(), timeout, candidateLimit);
        }
    }
}
