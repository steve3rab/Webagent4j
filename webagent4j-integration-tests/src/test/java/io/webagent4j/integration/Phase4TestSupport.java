package io.webagent4j.integration;

import io.webagent4j.browser.IBrowser;
import io.webagent4j.browser.IPage;
import io.webagent4j.core.WebAgent;
import java.io.IOException;

final class Phase4TestSupport implements AutoCloseable {

    private final ActionTestApplication application;
    private final IBrowser browser;

    private Phase4TestSupport(ActionTestApplication application, IBrowser browser) {
        this.application = application;
        this.browser = browser;
    }

    static Phase4TestSupport start() throws IOException {
        return new Phase4TestSupport(
                ActionTestApplication.start(),
                WebAgent.browser().playwright().chromium().headless(true).launch());
    }

    IPage open(String route) {
        return browser.open(application.url(route));
    }

    String url(String route) {
        return application.url(route);
    }

    int clickCount() {
        return application.clickCount();
    }

    @Override
    public void close() {
        browser.close();
        application.close();
    }
}
