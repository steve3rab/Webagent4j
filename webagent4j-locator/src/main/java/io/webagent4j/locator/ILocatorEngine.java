package io.webagent4j.locator;

import io.webagent4j.locator.api.LocatorDefinition;
import java.util.List;

/** Backend-neutral semantic locator orchestration contract. */
public interface ILocatorEngine {

    /** Resolves and explains the highest-ranked candidate. */
    LocatorResult locate(LocatorContext context, LocatorDefinition definition);

    /** Resolves one unambiguous candidate. */
    LocatorResult locateSingle(LocatorContext context, LocatorDefinition definition);

    /** Resolves all compatible candidates in deterministic rank order. */
    List<LocatorCandidate> locateAll(LocatorContext context, LocatorDefinition definition);
}
