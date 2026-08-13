package io.webagent4j.browser.playwright;

import com.microsoft.playwright.Page;
import io.webagent4j.action.IActionBuilder;
import io.webagent4j.action.internal.DefaultActionBuilder;
import io.webagent4j.browser.BrowserOptions;
import io.webagent4j.browser.IPage;
import io.webagent4j.common.BrowserException;
import io.webagent4j.dom.IElement;
import io.webagent4j.locator.ILocatorEngine;
import io.webagent4j.locator.LocatorConfig;
import io.webagent4j.locator.LocatorContext;
import io.webagent4j.locator.LocatorEngine;
import io.webagent4j.locator.LocatorResult;
import io.webagent4j.locator.api.IFind;
import io.webagent4j.locator.api.LocatorDefinition;
import io.webagent4j.observation.Observation;
import io.webagent4j.observation.ObservationEngine;
import io.webagent4j.observation.ObservationOptions;
import io.webagent4j.observation.spi.PageSnapshot;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/** Internal Playwright page adapter. */
final class PlaywrightPage implements IPage {

    private static final Set<String> ALLOWED_SCHEMES = Set.of("http", "https");

    private final Page page;
    private final BrowserOptions options;
    private final ILocatorEngine locatorEngine;
    private final PlaywrightLocatorBackend locatorBackend;
    private final ObservationEngine observationEngine;
    private final PlaywrightObservationBackend observationBackend;

    PlaywrightPage(Page page, BrowserOptions options) {
        this.page = page;
        this.options = options;
        this.locatorEngine = new LocatorEngine();
        this.locatorBackend =
                new PlaywrightLocatorBackend(
                        page,
                        locatorEngine,
                        LocatorConfig.builder()
                                .resolutionBudget(
                                        io.webagent4j.locator.LocatorResolutionBudget.defaults(
                                                options.timeouts().locator()))
                                .locale(options.locale())
                                .build());
        this.observationEngine = new ObservationEngine();
        this.observationBackend = new PlaywrightObservationBackend(page);
    }

    @Override
    public String url() {
        return page.url();
    }

    @Override
    public String title() {
        return page.title();
    }

    @Override
    public void navigate(String url) {
        validateUrl(url);
        page.navigate(
                url,
                new Page.NavigateOptions()
                        .setTimeout((double) options.timeouts().navigation().toMillis()));
    }

    @Override
    public void reload() {
        page.reload();
    }

    @Override
    public void goBack() {
        page.goBack();
    }

    @Override
    public void goForward() {
        page.goForward();
    }

    @Override
    public String content() {
        return page.content();
    }

    @Override
    public byte[] screenshot() {
        return page.screenshot(
                new Page.ScreenshotOptions()
                        .setType(com.microsoft.playwright.options.ScreenshotType.PNG));
    }

    @Override
    public Object evaluate(String expression) {
        if (expression == null || expression.isBlank()) {
            throw new IllegalArgumentException("expression cannot be blank");
        }
        return page.evaluate(expression);
    }

    @Override
    public Observation observe() {
        return observationEngine.observe(this);
    }

    @Override
    public Observation observe(ObservationOptions observationOptions) {
        return observationEngine.observe(this, observationOptions);
    }

    @Override
    public PageSnapshot captureObservation(ObservationOptions observationOptions) {
        return observationBackend.capture(
                Objects.requireNonNull(observationOptions, "observationOptions"));
    }

    @Override
    public IFind<IElement> find() {
        return locatorBackend.findOnPage();
    }

    @Override
    public IFind<IElement> find(LocatorConfig config) {
        return locatorBackend.findOnPage(Objects.requireNonNull(config, "config"));
    }

    @Override
    public LocatorResult locate(LocatorDefinition definition) {
        return locatorEngine.locate(locatorBackend.context(), definition);
    }

    @Override
    public LocatorResult locate(LocatorDefinition definition, LocatorConfig config) {
        return locatorEngine.locate(
                LocatorContext.page(locatorBackend, Objects.requireNonNull(config, "config")),
                definition);
    }

    @Override
    public IActionBuilder action() {
        return new DefaultActionBuilder(this);
    }

    @Override
    public void close() {
        if (!page.isClosed()) {
            page.close();
        }
    }

    private static void validateUrl(String url) {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("url cannot be blank");
        }
        try {
            URI uri = new URI(url);
            if (!uri.isAbsolute()
                    || !ALLOWED_SCHEMES.contains(uri.getScheme().toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException("only absolute HTTP(S) URLs are supported");
            }
        } catch (URISyntaxException exception) {
            throw new BrowserException("Invalid navigation URL: " + url, exception);
        }
    }
}
