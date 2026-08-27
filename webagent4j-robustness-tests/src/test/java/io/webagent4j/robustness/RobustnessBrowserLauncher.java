package io.webagent4j.robustness;

import io.webagent4j.browser.IBrowser;
import io.webagent4j.core.BrowserBuilder;
import io.webagent4j.core.WebAgent;

/**
 * Centralizes how every browser-backed adversarial suite launches its browser, so all of them use
 * the same {@link RobustnessBrowserEngine} selection instead of each test class repeating its own
 * engine switch. Launches exclusively through WebAgent4J's public consumer path ({@link
 * WebAgent#browser()}); no native Playwright type is referenced here or by callers.
 */
final class RobustnessBrowserLauncher {

    private RobustnessBrowserLauncher() {}

    /** Launches a headless browser for the currently selected {@link RobustnessBrowserEngine}. */
    static IBrowser launch() {
        return launch(RobustnessBrowserEngine.current());
    }

    /** Launches a headless browser for the given {@link RobustnessBrowserEngine}. */
    static IBrowser launch(RobustnessBrowserEngine engine) {
        BrowserBuilder builder = WebAgent.browser().playwright();
        builder =
                switch (engine) {
                    case CHROMIUM -> builder.chromium();
                    case FIREFOX -> builder.firefox();
                    case WEBKIT -> builder.webkit();
                };
        return builder.headless(true).launch();
    }
}
