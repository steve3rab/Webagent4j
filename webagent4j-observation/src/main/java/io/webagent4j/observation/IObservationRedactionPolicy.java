package io.webagent4j.observation;

import io.webagent4j.observation.spi.SnapshotElement;

/**
 * Central policy that decides whether a captured form value is omitted, retained, or irreversibly
 * redacted before semantic model construction.
 */
@FunctionalInterface
public interface IObservationRedactionPolicy {

    /** Produces safe value metadata without retaining a detected secret. */
    ObservedValue redact(SnapshotElement element, ObservationOptions options);
}
