package io.webagent4j.observation;

/** Injected structured event sink with no mutable global registration. */
@FunctionalInterface
public interface IObservationEventListener {

    /** Receives one immutable secret-safe event. */
    void onEvent(IObservationEvent event);

    /** Returns a listener that deliberately ignores events. */
    static IObservationEventListener none() {
        return ignored -> {};
    }
}
