package io.webagent4j.robustness;

/**
 * Test-only selector for which Playwright-backed engine the robustness suites launch through
 * WebAgent4J's public browser path, chosen via the {@code robustness.browser} system property so
 * the same adversarial corpus can be qualified against Chromium, Firefox, and WebKit without
 * changing test code per engine.
 *
 * <p>This type is test-support infrastructure local to {@code webagent4j-robustness-tests}; it is
 * not product/public API.
 */
enum RobustnessBrowserEngine {
    CHROMIUM,
    FIREFOX,
    WEBKIT;

    private static final String PROPERTY = "robustness.browser";

    /**
     * Resolves the engine to qualify from the {@code robustness.browser} system property, exactly
     * as {@code -Drobustness.browser=<value>} would set it on the Maven command line.
     */
    static RobustnessBrowserEngine current() {
        return fromPropertyValue(System.getProperty(PROPERTY, ""));
    }

    /**
     * Parses a raw {@code robustness.browser} value. An absent or blank value defaults to {@link
     * #CHROMIUM}, preserving existing behavior for callers that do not select an engine. An
     * explicitly provided value must be exactly one of {@code chromium}, {@code firefox}, {@code
     * webkit}; anything else -- including case variants and aliases such as {@code chrome} or
     * {@code ff} -- fails closed rather than being silently normalized or falling back to Chromium.
     */
    static RobustnessBrowserEngine fromPropertyValue(String rawValue) {
        String value = rawValue == null ? "" : rawValue.strip();
        if (value.isEmpty()) {
            return CHROMIUM;
        }
        return switch (value) {
            case "chromium" -> CHROMIUM;
            case "firefox" -> FIREFOX;
            case "webkit" -> WEBKIT;
            default ->
                    throw new IllegalArgumentException(
                            "Unknown "
                                    + PROPERTY
                                    + " value: '"
                                    + rawValue
                                    + "'. Expected one of: chromium, firefox, webkit.");
        };
    }

    /** The exact {@code robustness.browser} value that resolves back to this engine. */
    String propertyValue() {
        return switch (this) {
            case CHROMIUM -> "chromium";
            case FIREFOX -> "firefox";
            case WEBKIT -> "webkit";
        };
    }
}
