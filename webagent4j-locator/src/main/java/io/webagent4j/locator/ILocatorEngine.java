package io.webagent4j.locator;

import io.webagent4j.locator.api.LocatorDefinition;
import java.util.List;

/** Backend-neutral semantic locator orchestration contract. */
public interface ILocatorEngine {

    /** Resolves and explains the highest-ranked candidate. */
    default LocatorResult locate(LocatorContext context, LocatorDefinition definition) {
        return locate(ILiveLocatorContext.fixed(context), definition);
    }

    /**
     * Resolves and explains the highest-ranked candidate, re-resolving {@code context} fresh on
     * every polling attempt instead of once before the wait begins.
     */
    LocatorResult locate(ILiveLocatorContext context, LocatorDefinition definition);

    /** Resolves one unambiguous candidate. */
    default LocatorResult locateSingle(LocatorContext context, LocatorDefinition definition) {
        return locateSingle(ILiveLocatorContext.fixed(context), definition);
    }

    /**
     * Resolves one unambiguous candidate, re-resolving {@code context} fresh on every polling
     * attempt instead of once before the wait begins.
     */
    LocatorResult locateSingle(ILiveLocatorContext context, LocatorDefinition definition);

    /** Resolves all compatible candidates in deterministic rank order. */
    default List<LocatorCandidate> locateAll(LocatorContext context, LocatorDefinition definition) {
        return locateAll(ILiveLocatorContext.fixed(context), definition);
    }

    /**
     * Resolves all compatible candidates in deterministic rank order, re-resolving {@code context}
     * fresh on every polling attempt instead of once before the wait begins.
     */
    List<LocatorCandidate> locateAll(ILiveLocatorContext context, LocatorDefinition definition);
}
