package io.webagent4j.browser;

import io.webagent4j.common.Timeouts;
import java.util.Locale;
import java.util.Objects;

/**
 * Immutable browser launch configuration.
 *
 * @param browserType engine to launch
 * @param headless whether to hide the browser UI
 * @param timeouts categorized operation timeouts
 * @param locale browser-context locale
 */
public record BrowserOptions(
        BrowserType browserType, boolean headless, Timeouts timeouts, Locale locale) {

    /** Validates launch configuration. */
    public BrowserOptions {
        Objects.requireNonNull(browserType, "browserType");
        Objects.requireNonNull(timeouts, "timeouts");
        Objects.requireNonNull(locale, "locale");
    }

    /** Returns deterministic defaults using Chromium in headless mode. */
    public static BrowserOptions defaults() {
        return new BrowserOptions(BrowserType.CHROMIUM, true, Timeouts.defaults(), Locale.ENGLISH);
    }
}
