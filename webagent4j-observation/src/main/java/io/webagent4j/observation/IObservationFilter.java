package io.webagent4j.observation;

import io.webagent4j.observation.spi.SnapshotElement;

/** Deterministic relevance filter applied before public model construction. */
@FunctionalInterface
public interface IObservationFilter {

    /** Returns whether the captured semantic element should be retained. */
    boolean include(SnapshotElement element, ObservationOptions options);
}
