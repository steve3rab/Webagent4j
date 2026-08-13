package io.webagent4j.locator.api;

/** Deterministic text comparison modes supported by locator definitions. */
public enum TextMatchType {
    EXACT,
    CASE_INSENSITIVE_EXACT,
    CONTAINS,
    STARTS_WITH,
    ENDS_WITH,
    FUZZY,
    REGEX
}
