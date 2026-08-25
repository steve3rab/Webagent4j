package io.webagent4j.integration;

import io.webagent4j.browser.IBrowser;
import io.webagent4j.browser.IPage;
import io.webagent4j.core.WebAgent;
import java.io.IOException;
import java.time.Duration;

final class Phase4TestSupport implements AutoCloseable {

    private static final Duration CLICK_OBSERVATION_TIMEOUT = Duration.ofSeconds(5);

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

    int clickCount(String name) {
        return application.clickCount(name);
    }

    void awaitClickCount(String name, int expected) throws InterruptedException {
        long deadline = System.nanoTime() + CLICK_OBSERVATION_TIMEOUT.toNanos();

        while (System.nanoTime() < deadline) {
            if (clickCount(name) == expected) {
                return;
            }

            try {
                Thread.sleep(10);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw exception;
            }
        }

        throw new AssertionError(
                "Timed out waiting for click count '"
                        + name
                        + "' to become "
                        + expected
                        + "; actual="
                        + clickCount(name));
    }

    @Override
    public void close() {
        browser.close();
        application.close();
    }
}
