package io.webagent4j.locator.api;

/** Static entry points for programmatic locator definitions used by diagnostics APIs. */
public final class LocatorDefinitions {
    private LocatorDefinitions() {}

    /** Creates an unconstrained locator definition. */
    public static LocatorDefinition element() {
        return LocatorDefinition.element();
    }
}
