package io.webagent4j.locator;

/** Injected sink for structured locator lifecycle events. */
@FunctionalInterface
public interface ILocatorEventListener {

    /** Receives one immutable locator event. Implementations must avoid blocking resolution. */
    void onEvent(ILocatorEvent event);

    /** Returns a zero-cost listener used by default. */
    static ILocatorEventListener noOp() {
        return ignored -> {};
    }
}
