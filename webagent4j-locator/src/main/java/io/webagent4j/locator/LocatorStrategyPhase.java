package io.webagent4j.locator;

/** Execution phase used to integrate standard and custom strategies safely. */
public enum LocatorStrategyPhase {
    /** Deterministic strategies executed before any fuzzy fallback. */
    DETERMINISTIC,
    /** Conservative fallback strategies executed only when deterministic resolution found none. */
    FALLBACK
}
