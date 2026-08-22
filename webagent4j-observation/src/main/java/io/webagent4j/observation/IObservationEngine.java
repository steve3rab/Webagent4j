package io.webagent4j.observation;

import io.webagent4j.observation.spi.IObservationSource;

/**
 * Stateless semantic observation orchestrator.
 *
 * <p>An engine retains no per-observation mutable state. Sharing one instance across threads is
 * safe only when every collaborator supplied to its implementation is itself safe for concurrent
 * use. Callers must never concurrently use the same non-thread-safe source (a page or a frame).
 * Observation is passive and never intentionally clicks, submits, navigates, downloads, or uploads.
 */
public interface IObservationEngine {

    /** Observes a page or frame with standard secure limits. */
    Observation observe(IObservationSource source);

    /** Observes a page or frame with explicit immutable options. */
    Observation observe(IObservationSource source, ObservationOptions options);
}
