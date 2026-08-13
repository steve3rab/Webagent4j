package io.webagent4j.core;

import io.webagent4j.browser.BrowserOptions;
import io.webagent4j.browser.BrowserType;
import io.webagent4j.browser.IBrowser;
import io.webagent4j.browser.IBrowserProvider;
import io.webagent4j.common.BrowserException;
import io.webagent4j.common.Timeouts;
import java.util.Locale;
import java.util.Objects;
import java.util.ServiceLoader;

/** Fluent mutable builder for a backend-neutral browser launch. Builders are not thread-safe. */
public final class BrowserBuilder {

    private String providerId = "playwright";
    private BrowserType browserType = BrowserType.CHROMIUM;
    private boolean headless = true;
    private Timeouts timeouts = Timeouts.defaults();
    private Locale locale = Locale.ENGLISH;

    /** Selects the Playwright provider. */
    public BrowserBuilder playwright() {
        providerId = "playwright";
        return this;
    }

    /** Selects Chromium. */
    public BrowserBuilder chromium() {
        browserType = BrowserType.CHROMIUM;
        return this;
    }

    /** Selects whether the browser UI is hidden. */
    public BrowserBuilder headless(boolean value) {
        headless = value;
        return this;
    }

    /** Replaces categorized operation timeouts. */
    public BrowserBuilder timeouts(Timeouts value) {
        timeouts = Objects.requireNonNull(value, "value");
        return this;
    }

    /** Selects the browser context locale. */
    public BrowserBuilder locale(Locale value) {
        locale = Objects.requireNonNull(value, "value");
        return this;
    }

    /** Discovers the selected optional provider and launches the browser. */
    public IBrowser launch() {
        BrowserOptions options = new BrowserOptions(browserType, headless, timeouts, locale);
        return ServiceLoader.load(IBrowserProvider.class).stream()
                .map(ServiceLoader.Provider::get)
                .filter(provider -> provider.id().equals(providerId))
                .findFirst()
                .orElseThrow(
                        () ->
                                new BrowserException(
                                        "Browser provider '"
                                                + providerId
                                                + "' is not installed; add webagent4j-browser-playwright at runtime"))
                .launch(options);
    }
}
