package io.webagent4j.observation;

import io.webagent4j.locator.api.LocatorDefinition;
import io.webagent4j.observation.spi.SnapshotElement;

/** Creates a Phase 2 semantic locator definition from safe captured evidence. */
@FunctionalInterface
public interface ILocatorDefinitionFactory {

    /** Creates deterministic role/name/label/attribute re-location intent. */
    LocatorDefinition create(SnapshotElement element);
}
