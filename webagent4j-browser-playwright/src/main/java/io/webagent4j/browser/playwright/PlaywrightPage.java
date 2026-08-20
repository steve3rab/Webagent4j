package io.webagent4j.browser.playwright;

import com.microsoft.playwright.Page;
import io.webagent4j.action.IActionBackend;
import io.webagent4j.action.IActionBuilder;
import io.webagent4j.action.internal.DefaultActionBuilder;
import io.webagent4j.browser.BrowserOptions;
import io.webagent4j.browser.IFrameLocator;
import io.webagent4j.browser.IPage;
import io.webagent4j.browser.NavigationTimeoutException;
import io.webagent4j.dom.IElement;
import io.webagent4j.extraction.ExtractionEngine;
import io.webagent4j.extraction.api.ExtractedTable;
import io.webagent4j.extraction.api.ExtractionRequest;
import io.webagent4j.extraction.api.ExtractionResult;
import io.webagent4j.locator.ILiveLocatorContext;
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
import java.time.Duration;
import java.util.List;
import java.util.Objects;

/** Internal Playwright page adapter. */
final class PlaywrightPage implements IPage {

    private final Page page;
    private final BrowserOptions options;
    private final ILocatorEngine locatorEngine;
    private final ExtractionEngine extractionEngine;
    private final PlaywrightLocatorBackend locatorBackend;
    private final ObservationEngine observationEngine;
    private final PlaywrightObservationBackend observationBackend;
    private final PlaywrightActionBackend actionBackend;

    PlaywrightPage(Page page, BrowserOptions options) {
        this.page = page;
        this.options = options;
        this.locatorEngine = new LocatorEngine();
        this.extractionEngine = new ExtractionEngine(locatorEngine);
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
        this.actionBackend = new PlaywrightActionBackend(page, options);
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
        PlaywrightUrlValidator.requireAbsoluteHttp(url);
        page.navigate(
                url,
                new Page.NavigateOptions()
                        .setTimeout((double) options.timeouts().navigation().toMillis()));
    }

    /**
     * Overrides the default {@link IPage#navigate(String, Duration)}, mapping {@code timeout}
     * directly to Playwright's own {@link Page.NavigateOptions#setTimeout}, so it is genuinely
     * enforced rather than merely accepted. Playwright's native {@link
     * com.microsoft.playwright.TimeoutError} - a typed subclass of {@link
     * com.microsoft.playwright.PlaywrightException}, never inferred from an exception message - is
     * translated to the backend-neutral {@link NavigationTimeoutException} so callers such as
     * {@code BrowserCrawler} can classify a navigation timeout without depending on Playwright at
     * all. Any other Playwright failure propagates unchanged.
     */
    @Override
    public void navigate(String url, Duration timeout) {
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        PlaywrightUrlValidator.requireAbsoluteHttp(url);
        // Math.max(1, ...): a sub-millisecond-but-positive remaining budget must never floor to 0,
        // since Playwright treats a timeout of exactly 0 as "disabled" rather than "immediate".
        try {
            page.navigate(
                    url,
                    new Page.NavigateOptions()
                            .setTimeout((double) Math.max(1L, timeout.toMillis())));
        } catch (com.microsoft.playwright.TimeoutError e) {
            throw new NavigationTimeoutException(
                    "Navigation to " + url + " did not commit within " + timeout, e);
        }
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
    public <T> ExtractionResult<T> extract(ExtractionRequest<T> request) {
        return extractionEngine.extract(
                ILiveLocatorContext.fixed(locatorBackend.context()), request);
    }

    @Override
    public <T> ExtractionResult<List<T>> extractList(ExtractionRequest<T> request) {
        return extractionEngine.extractList(
                ILiveLocatorContext.fixed(locatorBackend.context()), request);
    }

    @Override
    public ExtractionResult<ExtractedTable> extractTable(LocatorDefinition source) {
        return extractionEngine.extractTable(
                ILiveLocatorContext.fixed(locatorBackend.context()), source);
    }

    @Override
    public IActionBuilder action() {
        return new DefaultActionBuilder(this);
    }

    @Override
    public IActionBackend actionBackend() {
        return actionBackend;
    }

    @Override
    public void close() {
        if (!page.isClosed()) {
            page.close();
        }
    }

    @Override
    public IFrameLocator frame() {
        return new PlaywrightFrameLocator(
                locatorEngine,
                locatorBackend.context(),
                List.of(),
                locatorBackend.context().config(),
                options,
                actionBackend);
    }
}
