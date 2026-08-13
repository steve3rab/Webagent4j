package io.webagent4j.locator;

/** Hierarchical locator scope kind. Frame support is represented but backend-dependent. */
public enum LocatorScopeType {
    /** Whole current page. */
    PAGE,
    /** Descendants of a re-resolvable element reference. */
    ELEMENT,
    /** Frame document; reserved for capable backends. */
    FRAME
}
