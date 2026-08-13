package io.webagent4j.browser.playwright;

import io.webagent4j.dom.IElement;
import io.webagent4j.locator.ILocatorEngine;
import io.webagent4j.locator.LocatorCandidate;
import io.webagent4j.locator.LocatorContext;
import io.webagent4j.locator.api.IElementReference;
import io.webagent4j.locator.api.ILocator;
import io.webagent4j.locator.api.LocatorDefinition;
import java.time.Duration;
import java.util.List;

/** Internal lazy fluent adapter backed by the shared semantic locator engine. */
final class PlaywrightLocator implements ILocator<IElement> {

    private final ILocatorEngine engine;
    private final LocatorContext context;
    private final LocatorDefinition definition;

    PlaywrightLocator(ILocatorEngine engine, LocatorContext context, LocatorDefinition definition) {
        this.engine = engine;
        this.context = context;
        this.definition = definition;
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
        return () -> engine.locateSingle(context, definition).element();
    }

    @Override
    public IElement first() {
        return engine.locate(context, definition).element();
    }

    @Override
    public IElement single() {
        return engine.locateSingle(context, definition).element();
    }

    @Override
    public List<IElement> all() {
        return engine.locateAll(context, definition).stream()
                .map(LocatorCandidate::element)
                .toList();
    }

    private ILocator<IElement> copy(LocatorDefinition next) {
        return new PlaywrightLocator(engine, context, next);
    }
}
