package io.webagent4j.observation;

/** Factual non-fatal issue detected during capture or semantic transformation. */
public enum ObservationWarningType {
    CAPTURE_MUTATED,
    ELEMENT_DISAPPEARED,
    UNSUPPORTED_ROLE,
    TABLE_PARTIAL,
    INVALID_ARIA_REFERENCE,
    INVALID_ARIA_VALUE,
    BUTTON_WITHOUT_NAME,
    IMAGE_WITHOUT_ALT,
    FORM_CONTROL_WITHOUT_LABEL,
    HEADING_LEVEL_JUMP,
    BACKEND_WARNING
}
