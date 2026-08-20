package io.webagent4j.extraction.api;

/** What raw datum an {@link ExtractionRequest} reads from a resolved element. */
public enum ExtractionReadType {
    /** The element's normalized visible text. */
    TEXT,
    /** One named HTML attribute. */
    ATTRIBUTE,
    /** The element's current live form-control value. */
    VALUE
}
