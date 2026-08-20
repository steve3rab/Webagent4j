package io.webagent4j.integration;

import io.webagent4j.browser.IBrowser;
import io.webagent4j.browser.IPage;
import io.webagent4j.core.WebAgent;
import java.io.IOException;

final class ExtractionTestSupport implements AutoCloseable {

    private final ExtractionTestApplication application;
    private final IBrowser browser;

    private ExtractionTestSupport(ExtractionTestApplication application, IBrowser browser) {
        this.application = application;
        this.browser = browser;
    }

    static ExtractionTestSupport start() throws IOException {
        return new ExtractionTestSupport(
                ExtractionTestApplication.start(),
                WebAgent.browser().playwright().chromium().headless(true).launch());
    }

    IPage open(String route) {
        return browser.open(application.url(route));
    }

    @Override
    public void close() {
        browser.close();
        application.close();
    }
}
