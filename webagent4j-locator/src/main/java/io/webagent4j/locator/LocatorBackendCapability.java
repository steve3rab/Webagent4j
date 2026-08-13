package io.webagent4j.locator;

/** Optional browser-backend locator capabilities beyond strategy execution. */
public enum LocatorBackendCapability {
    /** Native semantic locators and implicit HTML roles. */
    NATIVE_SEMANTICS,
    /** Re-resolvable locator references across DOM replacement. */
    RE_RESOLUTION,
    /** Hierarchical element-scoped queries. */
    SCOPED_SEARCH,
    /** Reliable current element-state inspection. */
    ELEMENT_STATE,
    /** Reliable viewport intersection checks. */
    VIEWPORT,
    /** Reliable center-point overlay coverage checks. */
    COVERAGE,
    /** Reliable click interactability checks. */
    INTERACTABILITY
}
