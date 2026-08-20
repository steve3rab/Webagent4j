/**
 * The immutable semantic observation model, options, renderers, diff, fingerprint, and the
 * batch-capture backend SPI ({@link io.webagent4j.observation.spi}) - depends on {@code
 * webagent4j-dom} and {@code webagent4j-locator-api} only, never on a browser backend.
 *
 * <p>{@link io.webagent4j.observation.Observation} is the result of one passive, bounded capture -
 * reading it never re-queries the live page. {@link io.webagent4j.observation.ObservationOptions}
 * bounds what a capture retains (element/depth/text/table limits, hidden-element and input-value
 * inclusion); applied limits always surface in {@link
 * io.webagent4j.observation.ObservationStatistics}, never silently. Password, token, and
 * credit-card controls are always redacted regardless of options - see {@link
 * io.webagent4j.observation.ObservedValue}.
 *
 * <p>The orchestrator that turns a backend's {@link io.webagent4j.observation.spi.PageSnapshot}
 * into this model - {@code IObservationEngine} - lives in {@code webagent4j-observation}, the
 * separate engine module this dependency inversion exists to allow: {@code IPage#observe()} exposes
 * only these immutable contracts and the capture SPI, never the engine or a backend type. See
 * {@code docs/observation.md} for the full semantic model and redaction contract.
 */
package io.webagent4j.observation;
