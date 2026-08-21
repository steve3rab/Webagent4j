package io.webagent4j.browser.playwright;

import com.microsoft.playwright.Page;
import io.webagent4j.action.IActionBackend;
import io.webagent4j.action.IActionBuilder;
import io.webagent4j.action.internal.DefaultActionBuilder;
import io.webagent4j.browser.BrowserOptions;
import io.webagent4j.browser.ConditionTimeoutException;
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
        requireWholeMillisecondTimeout(timeout);
        PlaywrightUrlValidator.requireAbsoluteHttp(url);
        try {
            page.navigate(url, new Page.NavigateOptions().setTimeout((double) timeout.toMillis()));
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

    /**
     * Overrides the default {@link IPage#waitForCondition(String, Duration)}, mapping directly onto
     * {@link Page#waitForFunction(String, Object, Page.WaitForFunctionOptions)} - Playwright's own
     * native, driver-enforced polling primitive - rather than a Java-side loop calling {@link
     * #evaluate(String)} repeatedly. {@code timeout} is enforced on the driver side, by Playwright
     * itself: the call either returns once {@code expression} is truthy or throws Playwright's
     * typed {@link com.microsoft.playwright.TimeoutError} - translated here to the backend-neutral
     * {@link ConditionTimeoutException}. That native timeout bound is the architectural guarantee
     * this method relies on: {@code BrowserCrawler} never falls back to an unbounded {@link
     * #evaluate(String)} loop that only a Java-side deadline could (imperfectly) police.
     *
     * <p>Separately, and empirically: in the Playwright version currently pinned by this project
     * (1.60.0), this wait has been exercised by real integration coverage ({@code
     * BrowserCrawlerRobustnessIT}'s client-side-navigation-during-stability regression) across a
     * client-side document navigation (a meta-refresh) occurring mid-wait, and it completes rather
     * than hanging or erroring. That specific cross-navigation resilience is an observed behavior
     * of the pinned Playwright version, proven by that test - not a documented, versioned contract
     * of the Playwright Java API asserted here as a universal guarantee. The property this method's
     * design actually depends on is narrower and does not rest on that behavior: {@code
     * waitForFunction} carries its own native timeout, so this call cannot hang past {@code
     * timeout} regardless of what happens to the execution context while it runs.
     *
     * <p>{@code waitForFunction} itself returns a {@link com.microsoft.playwright.JSHandle}
     * wrapping the truthy result, but this method deliberately never calls {@code
     * handle.jsonValue()} (a second, independent Playwright round-trip with no timeout of its own)
     * or {@code handle.dispose()} (likewise): doing either on the success path would reintroduce
     * exactly the kind of unbounded-call risk this whole method exists to eliminate - {@code
     * waitForFunction(timeout)} would still be bounded, but overall "did {@code waitForCondition}
     * return" would not be, since a call after it could still hang. This interface's contract
     * doesn't need the value (see {@link IPage#waitForCondition(String, Duration)}), so the handle
     * reference is simply not retained by WebAgent4j - this project makes no claim about, and does
     * not depend on, exactly when or how Playwright itself reclaims the underlying page-side
     * object.
     */
    @Override
    public void waitForCondition(String expression, Duration timeout) {
        if (expression == null || expression.isBlank()) {
            throw new IllegalArgumentException("expression cannot be blank");
        }
        requireWholeMillisecondTimeout(timeout);
        try {
            page.waitForFunction(
                    expression,
                    null,
                    new Page.WaitForFunctionOptions().setTimeout((double) timeout.toMillis()));
        } catch (com.microsoft.playwright.TimeoutError e) {
            throw new ConditionTimeoutException(
                    "Condition did not become true within " + timeout, e);
        }
    }

    /**
     * Validates {@code timeout} against exactly the same contract {@link IPage}'s private {@code
     * requireWholeMillisecondTimeout} enforces (positive, at least one millisecond, no
     * sub-millisecond remainder) - duplicated here, not shared via a new public utility, since that
     * validator is deliberately not part of {@link IPage}'s public surface.
     */
    private static void requireWholeMillisecondTimeout(Duration timeout) {
        if (timeout == null) {
            throw new IllegalArgumentException("timeout must not be null");
        }
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive, was " + timeout);
        }
        if (timeout.compareTo(Duration.ofMillis(1)) < 0) {
            throw new IllegalArgumentException(
                    "timeout must be at least 1 millisecond, was " + timeout);
        }
        if (timeout.getNano() % 1_000_000 != 0) {
            throw new IllegalArgumentException(
                    "timeout must use whole-millisecond precision, was " + timeout);
        }
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
