package io.webagent4j.browser.playwright;

import io.webagent4j.dom.IElement;
import io.webagent4j.locator.ILocatorEngine;
import io.webagent4j.locator.LocatorCandidate;
import io.webagent4j.locator.LocatorContext;
import io.webagent4j.locator.api.IElementReference;
import io.webagent4j.locator.api.ILocator;
import io.webagent4j.locator.api.ILocatorScope;
import io.webagent4j.locator.api.LocatorDefinition;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * Internal lazy fluent adapter backed by the shared semantic locator engine.
 *
 * <p>A pending scope (see {@link PlaywrightFind}) is never collapsed into a resolved {@link
 * LocatorContext} while the fluent chain is being built - not even an explicit element scope, since
 * applying it immediately while a structured scope declared elsewhere in the same chain stays
 * pending would silently reorder the chain relative to how the caller wrote it. Every terminal
 * operation - {@link #reference()}'s deferred resolution included - resolves the whole pending
 * chain fresh, strictly in declaration order, through {@link #resolveContext()}.
 */
final class PlaywrightLocator implements ILocator<IElement> {

    private final ILocatorEngine engine;
    private final LocatorContext baseContext;
    private final List<IPendingScope> pendingScopes;
    private final LocatorDefinition definition;

    PlaywrightLocator(
            ILocatorEngine engine,
            LocatorContext baseContext,
            List<IPendingScope> pendingScopes,
            LocatorDefinition definition) {
        this.engine = engine;
        this.baseContext = baseContext;
        this.pendingScopes = pendingScopes;
        this.definition = definition;
    }

    @Override
    public ILocator<IElement> within(IElement scope) {
        Objects.requireNonNull(scope, "scope");
        return new PlaywrightLocator(
                engine,
                baseContext,
                PlaywrightScopeResolver.append(pendingScopes, new IPendingScope.Element(scope)),
                definition);
    }

    @Override
    public ILocator<IElement> within(ILocatorScope<IElement> scope) {
        Objects.requireNonNull(scope, "scope");
        return new PlaywrightLocator(
                engine,
                baseContext,
                PlaywrightScopeResolver.append(pendingScopes, new IPendingScope.Structured(scope)),
                definition);
    }

    @Override
    public ILocator<IElement> named(String name) {
        return copy(definition.named(name));
    }

    @Override
    public ILocator<IElement> nameContaining(String text) {
        return copy(definition.nameContaining(text));
    }

    @Override
    public ILocator<IElement> fuzzyName(String name) {
        return copy(definition.fuzzyName(name));
    }

    @Override
    public ILocator<IElement> labelled(String label) {
        return copy(definition.labelled(label));
    }

    @Override
    public ILocator<IElement> visible() {
        return copy(definition.visibleOnly());
    }

    @Override
    public ILocator<IElement> hidden() {
        return copy(definition.hiddenOnly());
    }

    @Override
    public ILocator<IElement> enabled() {
        return copy(definition.enabledOnly());
    }

    @Override
    public ILocator<IElement> disabled() {
        return copy(definition.disabledOnly());
    }

    @Override
    public ILocator<IElement> editable() {
        return copy(definition.editableOnly());
    }

    @Override
    public ILocator<IElement> readonly() {
        return copy(definition.readOnlyOnly());
    }

    @Override
    public ILocator<IElement> checked() {
        return copy(definition.checkedOnly());
    }

    @Override
    public ILocator<IElement> selected() {
        return copy(definition.selectedOnly());
    }

    @Override
    public ILocator<IElement> focused() {
        return copy(definition.focusedOnly());
    }

    @Override
    public ILocator<IElement> inViewport() {
        return copy(definition.inViewportOnly());
    }

    @Override
    public ILocator<IElement> clickable() {
        return copy(definition.clickableOnly());
    }

    @Override
    public ILocator<IElement> covered() {
        return copy(definition.coveredOnly());
    }

    @Override
    public ILocator<IElement> timeout(Duration timeout) {
        return copy(definition.withTimeout(timeout));
    }

    @Override
    public ILocator<IElement> waitUntilVisible() {
        return copy(definition.waitingUntilVisible());
    }

    @Override
    public ILocator<IElement> stableFor(Duration duration) {
        return copy(definition.stableFor(duration));
    }

    @Override
    public IElementReference<IElement> reference() {
        return () -> engine.locateSingle(resolveContext(), definition).element();
    }

    @Override
    public IElement first() {
        return engine.locate(resolveContext(), definition).element();
    }

    @Override
    public IElement single() {
        return engine.locateSingle(resolveContext(), definition).element();
    }

    @Override
    public List<IElement> all() {
        return engine.locateAll(resolveContext(), definition).stream()
                .map(LocatorCandidate::element)
                .toList();
    }

    /**
     * Resolves the whole pending scope chain, strictly in declaration order, against the live DOM.
     * Called fresh by each terminal operation, including every invocation of a {@link
     * #reference()}'s deferred {@code resolve()} - so a target obtained long after the fluent chain
     * was built (a retried resolution, a re-run {@code IActionPlan.execute()}) always re-derives
     * its scopes in the order the caller declared them, instead of reusing a node captured once
     * when the chain was assembled or regrouping explicit and structured scopes by kind.
     */
    private LocatorContext resolveContext() {
        return PlaywrightScopeResolver.resolvePendingScopes(engine, baseContext, pendingScopes);
    }

    private ILocator<IElement> copy(LocatorDefinition next) {
        return new PlaywrightLocator(engine, baseContext, pendingScopes, next);
    }
}
