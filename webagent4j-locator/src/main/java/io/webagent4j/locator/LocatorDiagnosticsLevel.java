package io.webagent4j.locator;

/** Controls how much diagnostic data is retained during resolution. */
public enum LocatorDiagnosticsLevel {
    /** Retains only data required to resolve the element. */
    OFF,
    /** Retains strategy summaries, counts, selection, duration and budget information. */
    BASIC,
    /** Also retains bounded candidate rejection details and individual evidence. */
    DETAILED
}
