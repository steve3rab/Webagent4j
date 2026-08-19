package io.webagent4j.observation;

import io.webagent4j.observation.spi.IObservationSource;

/**
 * Thread-safe semantic observation orchestrator.
 *
 * <p>An engine instance may be shared across threads, but callers must not concurrently use the
 * same non-thread-safe source (a page or a frame). Observation is passive and never intentionally
 * clicks, submits, navigates, downloads, or uploads.
 */
public interface IObservationEngine {

    /** Observes a page or frame with standard secure limits. */
    Observation observe(IObservationSource source);

    /** Observes a page or frame with explicit immutable options. */
    Observation observe(IObservationSource source, ObservationOptions options);
}
