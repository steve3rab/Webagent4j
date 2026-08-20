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

    /**
     * Resolves all compatible candidates together with the scope path actually used to find them.
     * See {@link LocatorAllResult}. The default implementation falls back to {@code context}'s
     * starting baseline scope path, for implementations that do not separately track the
     * live-resolved scope; {@link LocatorEngine} overrides this with the real resolved scope from
     * the same resolution attempt, never a second independent live resolution.
     */
    default LocatorAllResult locateAllWithScopePath(
            LocatorContext context, LocatorDefinition definition) {
        return locateAllWithScopePath(ILiveLocatorContext.fixed(context), definition);
    }

    /**
     * Resolves all compatible candidates together with the scope path actually used to find them,
     * re-resolving {@code context} fresh on every polling attempt instead of once before the wait
     * begins. See {@link #locateAllWithScopePath(LocatorContext, LocatorDefinition)}.
     */
    default LocatorAllResult locateAllWithScopePath(
            ILiveLocatorContext context, LocatorDefinition definition) {
        return new LocatorAllResult(
                locateAll(context, definition), context.baseline().scope().path());
    }
}
