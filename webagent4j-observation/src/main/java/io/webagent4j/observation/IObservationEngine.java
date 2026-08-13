package io.webagent4j.observation;

import io.webagent4j.browser.IPage;

/**
 * Thread-safe semantic observation orchestrator.
 *
 * <p>An engine instance may be shared across threads, but callers must not concurrently use the
 * same non-thread-safe page. Observation is passive and never intentionally clicks, submits,
 * navigates, downloads, or uploads.
 */
public interface IObservationEngine {

    /** Observes a page with standard secure limits. */
    Observation observe(IPage page);

    /** Observes a page with explicit immutable options. */
    Observation observe(IPage page, ObservationOptions options);
}
