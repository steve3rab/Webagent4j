package io.webagent4j.observation;

/**
 * Stateless rendering strategy for an immutable observation.
 *
 * @param <R> rendered output type
 */
@FunctionalInterface
public interface IObservationRenderer<R> {

    /** Renders the supplied immutable snapshot without browser access. */
    R render(Observation observation);
}
