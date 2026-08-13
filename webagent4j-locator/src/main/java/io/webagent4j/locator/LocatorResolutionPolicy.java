package io.webagent4j.locator;

/** Resolution strictness applied by the deterministic locator pipeline. */
public enum LocatorResolutionPolicy {
    /** Exact deterministic matches only; fuzzy strategies are skipped. */
    STRICT,
    /** Exact-first resolution with a conservative fuzzy fallback. */
    BALANCED,
    /** More tolerant fuzzy fallback with detailed diagnostics. */
    PERMISSIVE
}
