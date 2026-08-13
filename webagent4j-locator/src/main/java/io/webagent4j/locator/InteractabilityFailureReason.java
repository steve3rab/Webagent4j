package io.webagent4j.locator;

/** Primary reasons why an element cannot safely receive an interaction. */
public enum InteractabilityFailureReason {
    NOT_VISIBLE,
    DISABLED,
    DETACHED,
    COVERED,
    OUTSIDE_VIEWPORT,
    READ_ONLY,
    UNKNOWN
}
