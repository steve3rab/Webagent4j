package io.webagent4j.integration;

import io.webagent4j.browser.IBrowser;
import io.webagent4j.browser.IPage;
import io.webagent4j.core.WebAgent;
import java.io.IOException;

final class FramePhase4TestSupport implements AutoCloseable {

    private final FrameTestApplication application;
    private final FrameTestApplication crossOriginApplication;
    private final IBrowser browser;

    private FramePhase4TestSupport(
            FrameTestApplication application,
            FrameTestApplication crossOriginApplication,
            IBrowser browser) {
        this.application = application;
        this.crossOriginApplication = crossOriginApplication;
        this.browser = browser;
    }

    static FramePhase4TestSupport start() throws IOException {
        return new FramePhase4TestSupport(
                FrameTestApplication.start(),
                null,
                WebAgent.browser().playwright().chromium().headless(true).launch());
    }

    /**
     * Starts a second independent fixture server on a different loopback port for cross-origin ITs.
     */
    static FramePhase4TestSupport startWithCrossOrigin() throws IOException {
        FrameTestApplication crossOrigin = FrameTestApplication.start();
        FrameTestApplication main = FrameTestApplication.start(crossOrigin.url(""));
        return new FramePhase4TestSupport(
                main,
                crossOrigin,
                WebAgent.browser().playwright().chromium().headless(true).launch());
    }

    IPage open(String route) {
        return browser.open(application.url(route));
    }

    String url(String route) {
        return application.url(route);
    }

    int clickCount(String name) {
        return application.clickCount(name);
    }

    int crossOriginClickCount(String name) {
        return crossOriginApplication.clickCount(name);
    }

    @Override
    public void close() {
        browser.close();
        application.close();
        if (crossOriginApplication != null) {
            crossOriginApplication.close();
        }
    }
}
