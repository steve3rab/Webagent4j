package io.webagent4j.locator;

import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable declaration of backend strategy and state capabilities. The engine uses this contract
 * instead of checking concrete adapter types.
 *
 * @param strategies executable discovery strategies
 * @param features optional advanced features
 */
public record LocatorBackendCapabilities(
        Set<LocatorStrategyType> strategies, Set<LocatorBackendCapability> features) {

    /** Defensively copies capability sets. */
    public LocatorBackendCapabilities {
        strategies = Set.copyOf(Objects.requireNonNull(strategies, "strategies"));
        features = Set.copyOf(Objects.requireNonNull(features, "features"));
    }

    /** Returns standard strategy support without claiming advanced backend behavior. */
    public static LocatorBackendCapabilities standardStrategies() {
        EnumSet<LocatorStrategyType> supported = EnumSet.allOf(LocatorStrategyType.class);
        supported.remove(LocatorStrategyType.CUSTOM);
        return new LocatorBackendCapabilities(supported, Set.of());
    }

    /** Returns whether a discovery strategy is supported. */
    public boolean supports(LocatorStrategyType strategy) {
        return strategies.contains(Objects.requireNonNull(strategy, "strategy"));
    }

    /** Returns whether an advanced backend feature is supported. */
    public boolean supports(LocatorBackendCapability feature) {
        return features.contains(Objects.requireNonNull(feature, "feature"));
    }
}
