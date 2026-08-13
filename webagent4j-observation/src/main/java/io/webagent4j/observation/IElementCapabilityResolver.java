package io.webagent4j.observation;

import java.util.Set;

/** Resolves only action capabilities reliably supported by a semantic element. */
@FunctionalInterface
public interface IElementCapabilityResolver {

    /** Returns immutable capabilities for the supplied element. */
    Set<ElementCapability> resolve(SemanticElement element);
}
