package io.webagent4j.browser.playwright;

import com.microsoft.playwright.ElementHandle;
import com.microsoft.playwright.Frame;
import com.microsoft.playwright.Locator;
import io.webagent4j.action.IActionBackend;
import io.webagent4j.action.IActionBuilder;
import io.webagent4j.action.internal.DefaultActionBuilder;
import io.webagent4j.browser.BrowserOptions;
import io.webagent4j.browser.IFrame;
import io.webagent4j.browser.IFrameLocator;
import io.webagent4j.common.BrowserException;
import io.webagent4j.dom.IElement;
import io.webagent4j.extraction.ExtractionEngine;
import io.webagent4j.extraction.api.ExtractedTable;
import io.webagent4j.extraction.api.ExtractionRequest;
import io.webagent4j.extraction.api.ExtractionResult;
import io.webagent4j.locator.ILiveLocatorContext;
import io.webagent4j.locator.ILocatorEngine;
import io.webagent4j.locator.LocatorConfig;
import io.webagent4j.locator.LocatorContext;
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

/**
 * Internal Playwright frame adapter.
 *
 * <p>Holds no {@link com.microsoft.playwright.Frame} or {@link
 * com.microsoft.playwright.FrameLocator} directly: its identity is the pending-scope chain (see
 * {@link IPendingScope}) that re-resolves this frame - and any scopes declared before it - fresh on
 * every operation through {@link PlaywrightScopeResolver}. This is what makes an {@code IFrame}
 * obtained long ago (before an {@link io.webagent4j.action.IActionPlan#execute()} much later, for
 * example) keep working correctly across frame navigation and frame replacement, and fail
 * explicitly - never silently reusing a detached or superseded document - once its semantic
 * identity can no longer be resolved unambiguously.
 */
final class PlaywrightFrame implements IFrame {

    private final ILocatorEngine engine;
    private final ExtractionEngine extractionEngine;
    private final LocatorContext baseContext;
    private final List<IPendingScope> pendingScopes;
    private final LocatorConfig config;
    private final BrowserOptions options;
    private final PlaywrightActionBackend actionBackend;
    private final ObservationEngine observationEngine;

    PlaywrightFrame(
            ILocatorEngine engine,
            LocatorContext baseContext,
            List<IPendingScope> pendingScopes,
            LocatorConfig config,
            BrowserOptions options,
            PlaywrightActionBackend actionBackend) {
        this.engine = engine;
        this.extractionEngine = new ExtractionEngine(engine);
        this.baseContext = baseContext;
        this.pendingScopes = pendingScopes;
        this.config = config;
        this.options = options;
        this.actionBackend = actionBackend;
        this.observationEngine = new ObservationEngine();
    }

    @Override
    public String url() {
        return resolveFrame().url();
    }

    @Override
    public String title() {
        return resolveFrame().title();
    }

    @Override
    public void navigate(String url) {
        PlaywrightUrlValidator.requireAbsoluteHttp(url);
        resolveFrame()
                .navigate(
                        url,
                        new Frame.NavigateOptions()
                                .setTimeout((double) options.timeouts().navigation().toMillis()));
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
        Objects.requireNonNull(observationOptions, "observationOptions");
        Frame frame = resolveFrame();
        return new PlaywrightObservationBackend(frame::evaluate).capture(observationOptions);
    }

    @Override
    public IFind<IElement> find() {
        return PlaywrightFind.withPendingScopes(engine, baseContext, pendingScopes);
    }

    @Override
    public IFind<IElement> find(LocatorConfig overrideConfig) {
        Objects.requireNonNull(overrideConfig, "config");
        LocatorContext overriddenBase =
                new LocatorContext(baseContext.backend(), baseContext.scope(), overrideConfig);
        return PlaywrightFind.withPendingScopes(engine, overriddenBase, pendingScopes);
    }

    @Override
    public LocatorResult locate(LocatorDefinition definition) {
        return engine.locate(
                PlaywrightScopeResolver.liveContext(
                        engine, baseContext, pendingScopes, baseContext.timeoutFor(definition)),
                definition);
    }

    @Override
    public LocatorResult locate(LocatorDefinition definition, LocatorConfig overrideConfig) {
        Objects.requireNonNull(overrideConfig, "config");
        LocatorContext overriddenBase =
                new LocatorContext(baseContext.backend(), baseContext.scope(), overrideConfig);
        return engine.locate(
                liveContext(overrideConfig, overriddenBase.timeoutFor(definition)), definition);
    }

    @Override
    public <T> ExtractionResult<T> extract(ExtractionRequest<T> request) {
        return extractionEngine.extract(
                PlaywrightScopeResolver.liveContext(
                        engine,
                        baseContext,
                        pendingScopes,
                        baseContext.timeoutFor(request.source())),
                request);
    }

    @Override
    public <T> ExtractionResult<List<T>> extractList(ExtractionRequest<T> request) {
        return extractionEngine.extractList(
                PlaywrightScopeResolver.liveContext(
                        engine,
                        baseContext,
                        pendingScopes,
                        baseContext.timeoutFor(request.source())),
                request);
    }

    @Override
    public ExtractionResult<ExtractedTable> extractTable(LocatorDefinition source) {
        return extractionEngine.extractTable(
                PlaywrightScopeResolver.liveContext(
                        engine, baseContext, pendingScopes, baseContext.timeoutFor(source)),
                source);
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
    public IFrameLocator frame() {
        return new PlaywrightFrameLocator(
                engine, baseContext, pendingScopes, config, options, actionBackend);
    }

    /**
     * Returns a live context that re-resolves this frame's own pending-scope chain fresh against
     * the live DOM on every call, with {@code overrideConfig} applied to each fresh resolution -
     * the same live-resolution guarantee {@link #find(LocatorConfig)} already gives, extended to
     * {@link #locate(LocatorDefinition, LocatorConfig)} so a frame that disappears, is replaced, or
     * becomes ambiguous mid-wait is caught on the very next poll rather than only when the wait
     * begins.
     */
    private ILiveLocatorContext liveContext(LocatorConfig overrideConfig, Duration timeout) {
        ILiveLocatorContext pendingContext =
                PlaywrightScopeResolver.liveContext(engine, baseContext, pendingScopes, timeout);
        return new ILiveLocatorContext() {
            @Override
            public LocatorContext baseline() {
                return new LocatorContext(
                        baseContext.backend(), baseContext.scope(), overrideConfig);
            }

            @Override
            public LocatorContext resolve() {
                LocatorContext resolved = pendingContext.resolve();
                return new LocatorContext(resolved.backend(), resolved.scope(), overrideConfig);
            }
        };
    }

    /**
     * Re-resolves this frame down to a concrete, current {@link Frame} snapshot - needed only for
     * operations ({@code url()}, {@code title()}, {@code navigate()}, observation capture) that
     * require a real Playwright {@code Frame} rather than a lazily-resolving document root.
     */
    private Frame resolveFrame() {
        IPendingScope.Frame lastEntry =
                (IPendingScope.Frame) pendingScopes.get(pendingScopes.size() - 1);
        List<IPendingScope> parentScopes = pendingScopes.subList(0, pendingScopes.size() - 1);
        LocatorResult iframe =
                PlaywrightScopeResolver.resolveTerminalFrameElement(
                        engine,
                        baseContext,
                        parentScopes,
                        lastEntry.definition(),
                        config.resolutionBudget().timeout());
        Locator iframeLocator = PlaywrightLocatorBackend.unwrap(iframe.element());
        ElementHandle handle = iframeLocator.elementHandle();
        Frame frame = handle.contentFrame();
        if (frame == null) {
            throw new BrowserException(
                    "Frame element resolved but its content document is not available");
        }
        return frame;
    }
}
