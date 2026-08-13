package io.webagent4j.locator;

import java.util.List;

/** Immutable registry contract for standard and custom locator strategies. */
public interface ILocatorStrategyRegistry {

    /** Returns all registered strategies in deterministic bootstrap order. */
    List<ILocatorStrategy> strategies();

    /** Returns the registered strategy for a plan step. */
    ILocatorStrategy strategy(LocatorStrategyType type);
}
