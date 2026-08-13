package io.webagent4j.locator;

/** Classification of one piece of candidate evidence. */
public enum LocatorMatchType {
    /** Exact deterministic semantic or text match. */
    EXACT,
    /** Conservative fuzzy text similarity. */
    FUZZY,
    /** Exact attribute, selector or test-id match. */
    ATTRIBUTE,
    /** Observed state used as a preference or constraint explanation. */
    STATE
}
