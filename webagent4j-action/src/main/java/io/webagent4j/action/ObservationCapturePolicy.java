package io.webagent4j.action;

/** Controls potentially expensive before and after semantic observations. */
public enum ObservationCapturePolicy {
    NONE,
    ON_FAILURE,
    ALWAYS,
    WHEN_REQUIRED
}
