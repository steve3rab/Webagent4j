package io.webagent4j.observation.spi;

import io.webagent4j.observation.ObservationOptions;

/**
 * Backend SPI for one passive, bounded, batch-oriented semantic page capture.
 *
 * <p>Implementations must not click, submit, navigate, download, or otherwise trigger intentional
 * business actions while capturing a snapshot.
 */
@FunctionalInterface
public interface IObservationSource {

    /** Captures backend-neutral data using the supplied secure limits. */
    PageSnapshot captureObservation(ObservationOptions options);
}
