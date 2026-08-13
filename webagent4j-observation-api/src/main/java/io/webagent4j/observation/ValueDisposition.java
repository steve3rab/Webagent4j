package io.webagent4j.observation;

/** Describes why an observed form value is or is not retained. */
public enum ValueDisposition {
    EMPTY,
    OMITTED,
    PLAIN,
    REDACTED
}
