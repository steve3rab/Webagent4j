package io.webagent4j.browser.playwright;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType.LaunchOptions;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import io.webagent4j.browser.BrowserOptions;
import io.webagent4j.browser.IBrowser;
import io.webagent4j.browser.IPage;
import io.webagent4j.common.BrowserException;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Internal Playwright browser adapter. */
final class PlaywrightBrowser implements IBrowser {

    private static final Logger LOGGER = LoggerFactory.getLogger(PlaywrightBrowser.class);

    private final Playwright playwright;
    private final Browser browser;
    private final BrowserContext context;
    private final BrowserOptions options;
    private final Map<Page, PlaywrightPage> pages = new IdentityHashMap<>();
    private boolean closed;

    private PlaywrightBrowser(
            Playwright playwright,
            Browser browser,
            BrowserContext context,
            BrowserOptions options) {
        this.playwright = playwright;
        this.browser = browser;
        this.context = context;
        this.options = options;
    }

    @SuppressWarnings("java:S2095") // Ownership is transferred to the returned adapter.
    static PlaywrightBrowser launch(BrowserOptions options) {
        LOGGER.debug(
                "Launching {} through Playwright (headless={})",
                options.browserType(),
                options.headless());
        Playwright playwright = Playwright.create();
        try {
            LaunchOptions launchOptions =
                    new LaunchOptions()
                            .setHeadless(options.headless())
                            .setTimeout((double) options.timeouts().navigation().toMillis());
            Browser browser =
                    switch (options.browserType()) {
                        case CHROMIUM -> playwright.chromium().launch(launchOptions);
                        case FIREFOX -> playwright.firefox().launch(launchOptions);
                        case WEBKIT -> playwright.webkit().launch(launchOptions);
                    };
            BrowserContext context =
                    browser.newContext(
                            new Browser.NewContextOptions()
                                    .setLocale(options.locale().toLanguageTag()));
            context.setDefaultTimeout(options.timeouts().action().toMillis());
            context.setDefaultNavigationTimeout(options.timeouts().navigation().toMillis());
            return new PlaywrightBrowser(playwright, browser, context, options);
        } catch (RuntimeException exception) {
            playwright.close();
            throw new BrowserException(
                    "Playwright could not launch the selected browser", exception);
        }
    }

    @Override
    public IPage newPage() {
        ensureOpen();
        Page nativePage = context.newPage();
        PlaywrightPage page = new PlaywrightPage(nativePage, options);
        pages.put(nativePage, page);
        return page;
    }

    @Override
    public IPage open(String url) {
        IPage page = newPage();
        try {
            page.navigate(url);
            return page;
        } catch (RuntimeException exception) {
            page.close();
            throw exception;
        }
    }

    @Override
    public IPage currentPage() {
        List<IPage> snapshot = pages();
        if (snapshot.isEmpty()) {
            throw new BrowserException("The browser has no open pages");
        }
        return snapshot.getLast();
    }

    @Override
    public List<IPage> pages() {
        ensureOpen();
        List<IPage> snapshot = new ArrayList<>();
        for (Page nativePage : context.pages()) {
            if (!nativePage.isClosed()) {
                snapshot.add(
                        pages.computeIfAbsent(
                                nativePage, page -> new PlaywrightPage(page, options)));
            }
        }
        return List.copyOf(snapshot);
    }

    @Override
    public void close() {
        if (!closed) {
            closed = true;
            try {
                context.close();
            } finally {
                try {
                    browser.close();
                } finally {
                    playwright.close();
                }
            }
            LOGGER.debug("Closed Playwright browser and context");
        }
    }

    private void ensureOpen() {
        if (closed) {
            throw new BrowserException("The browser is closed");
        }
    }
}
