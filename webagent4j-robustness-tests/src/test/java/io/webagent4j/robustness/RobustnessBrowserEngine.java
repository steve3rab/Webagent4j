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
     * as {@code -Drobustness.browser=<value>} would set it on the Maven command line. An absent
     * property (not just an absent value) is distinguished from an explicitly supplied one; both
     * {@link #fromPropertyValue} and its caller must preserve that distinction, since an explicit
     * value that happens to be whitespace-only is real invalid input, not absence.
     */
    static RobustnessBrowserEngine current() {
        return fromPropertyValue(System.getProperty(PROPERTY));
    }

    /**
     * Parses a raw {@code robustness.browser} value exactly as given, with no trimming or other
     * normalization. Absence ({@code null}) or an explicit empty string default to {@link
     * #CHROMIUM}, preserving existing behavior for callers that do not select an engine. Any other
     * value must be exactly one of {@code chromium}, {@code firefox}, {@code webkit} -- anything
     * else fails closed rather than being silently normalized or falling back to Chromium,
     * including: leading/trailing whitespace (e.g. {@code " firefox "}), a whitespace-only value (a
     * real explicit input, not an absent one), case variants (e.g. {@code "Chromium"}), and aliases
     * such as {@code chrome} or {@code ff}.
     */
    static RobustnessBrowserEngine fromPropertyValue(String rawValue) {
        if (rawValue == null || rawValue.isEmpty()) {
            return CHROMIUM;
        }
        return switch (rawValue) {
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
