package io.webagent4j.locator;

/** Deterministic discovery strategies available to the semantic locator engine. */
public enum LocatorStrategyType {
    ROLE,
    ACCESSIBLE_NAME,
    LABEL,
    ID,
    NAME_ATTRIBUTE,
    PLACEHOLDER,
    TITLE,
    ALT_TEXT,
    VISIBLE_TEXT,
    DOM_RELATION,
    CSS,
    XPATH,
    FUZZY_TEXT,
    ATTRIBUTE,
    TEST_ID,
    CUSTOM
}
