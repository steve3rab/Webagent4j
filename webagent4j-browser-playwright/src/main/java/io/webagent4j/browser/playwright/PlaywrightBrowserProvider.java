package io.webagent4j.browser.playwright;

import io.webagent4j.browser.BrowserOptions;
import io.webagent4j.browser.IBrowser;
import io.webagent4j.browser.IBrowserProvider;
import java.util.Objects;

/**
 * Playwright service provider discovered by the core facade through {@link
 * java.util.ServiceLoader}.
 */
public final class PlaywrightBrowserProvider implements IBrowserProvider {

    @Override
    public String id() {
        return "playwright";
    }

    @Override
    public IBrowser launch(BrowserOptions options) {
        return PlaywrightBrowser.launch(Objects.requireNonNull(options, "options"));
    }
}
