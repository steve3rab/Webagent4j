package io.webagent4j.browser.playwright;

import io.webagent4j.browser.BrowserOptions;
import io.webagent4j.browser.FrameDefinition;
import io.webagent4j.browser.IFrame;
import io.webagent4j.browser.IFrameLocator;
import io.webagent4j.common.LocatorFailureClassifier;
import io.webagent4j.locator.ILocatorEngine;
import io.webagent4j.locator.LocatorConfig;
import io.webagent4j.locator.LocatorContext;
import io.webagent4j.locator.api.TextMatch;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Internal fluent frame query entry point. Mirrors {@link PlaywrightFind}: neither construction nor
 * any {@code with*} call resolves anything - a frame is only ever looked up at a terminal operation
 * ({@link #single()}, {@link #tryFind()}), with real {@link io.webagent4j.wait.WaitEngine}-driven
 * waiting up to the requested timeout, exactly like {@link
 * io.webagent4j.locator.api.ILocator#single()} does for elements.
 */
final class PlaywrightFrameLocator implements IFrameLocator {

    private final ILocatorEngine engine;
    private final LocatorContext baseContext;
    private final List<IPendingScope> parentPendingScopes;
    private final LocatorConfig config;
    private final BrowserOptions options;
    private final PlaywrightActionBackend actionBackend;
    private final FrameDefinition definition;

    PlaywrightFrameLocator(
            ILocatorEngine engine,
            LocatorContext baseContext,
            List<IPendingScope> parentPendingScopes,
            LocatorConfig config,
            BrowserOptions options,
            PlaywrightActionBackend actionBackend) {
        this(
                engine,
                baseContext,
                parentPendingScopes,
                config,
                options,
                actionBackend,
                FrameDefinition.frame());
    }

    private PlaywrightFrameLocator(
            ILocatorEngine engine,
            LocatorContext baseContext,
            List<IPendingScope> parentPendingScopes,
            LocatorConfig config,
            BrowserOptions options,
            PlaywrightActionBackend actionBackend,
            FrameDefinition definition) {
        this.engine = engine;
        this.baseContext = baseContext;
        this.parentPendingScopes = parentPendingScopes;
        this.config = config;
        this.options = options;
        this.actionBackend = actionBackend;
        this.definition = definition;
    }

    @Override
    public IFrameLocator withId(String id) {
        return copy(definition.withId(id));
    }

    @Override
    public IFrameLocator named(String name) {
        return copy(definition.named(name));
    }

    @Override
    public IFrameLocator withTitle(String title) {
        return copy(definition.withTitle(title));
    }

    @Override
    public IFrameLocator withUrl(TextMatch match) {
        return copy(definition.withUrl(match));
    }

    @Override
    public IFrameLocator timeout(Duration timeout) {
        return copy(definition.withTimeout(timeout));
    }

    @Override
    public IFrameLocator stableFor(Duration duration) {
        return copy(definition.stableFor(duration));
    }

    /**
     * Returns the one matching frame or fails for zero or multiple matches.
     *
     * <p>There is no scoring dimension for frames to rank candidates by, and DOM order is
     * deliberately never used as a hidden tie breaker, so a frame query with more than one equally
     * valid match is always ambiguous rather than resolved to "the first one".
     */
    @Override
    public IFrame single() {
        Duration effectiveTimeout =
                definition.timeout().orElse(config.resolutionBudget().timeout());
        PlaywrightScopeResolver.resolveTerminalFrameElement(
                engine, baseContext, parentPendingScopes, definition, effectiveTimeout);
        List<IPendingScope> resolvedScopes =
                PlaywrightScopeResolver.append(
                        parentPendingScopes, new IPendingScope.Frame(definition));
        return new PlaywrightFrame(
                engine, baseContext, resolvedScopes, config, options, actionBackend);
    }

    @Override
    public Optional<IFrame> tryFind() {
        try {
            return Optional.of(single());
        } catch (RuntimeException failure) {
            if (LocatorFailureClassifier.isNotFound(failure)) {
                return Optional.empty();
            }
            throw failure;
        }
    }

    private IFrameLocator copy(FrameDefinition next) {
        return new PlaywrightFrameLocator(
                engine, baseContext, parentPendingScopes, config, options, actionBackend, next);
    }
}
