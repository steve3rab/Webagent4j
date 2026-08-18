package io.webagent4j.browser.playwright;

import io.webagent4j.dom.IElement;
import io.webagent4j.locator.ILocatorEngine;
import io.webagent4j.locator.LocatorContext;
import io.webagent4j.locator.api.ElementRole;
import io.webagent4j.locator.api.IFind;
import io.webagent4j.locator.api.ILocator;
import io.webagent4j.locator.api.ILocatorScope;
import io.webagent4j.locator.api.LocatorDefinition;
import io.webagent4j.locator.api.TextMatch;
import java.util.List;
import java.util.Objects;

/**
 * Internal fluent entry point delegating every terminal operation to the shared locator engine.
 *
 * <p>An explicit element scope ({@link #within(IElement)}) is applied eagerly: the caller handed
 * over a concrete node, so there is nothing left to re-resolve. A structured scope ({@link
 * #within(ILocatorScope)}) is instead kept as a pending, backend-neutral definition and resolved
 * fresh at every terminal operation - see {@link PlaywrightLocator} - so a semantic region such as
 * "the section labelled Shipping" is never frozen into a single DOM node captured once at chain-
 * build time.
 */
final class PlaywrightFind implements IFind<IElement> {

    private final ILocatorEngine engine;
    private final LocatorContext context;
    private final List<ILocatorScope<IElement>> pendingScopes;

    PlaywrightFind(ILocatorEngine engine, LocatorContext context) {
        this(engine, context, List.of());
    }

    private PlaywrightFind(
            ILocatorEngine engine,
            LocatorContext context,
            List<ILocatorScope<IElement>> pendingScopes) {
        this.engine = engine;
        this.context = context;
        this.pendingScopes = pendingScopes;
    }

    @Override
    public IFind<IElement> within(IElement scope) {
        return new PlaywrightFind(
                engine, PlaywrightScopeResolver.resolveElementScope(context, scope), pendingScopes);
    }

    @Override
    public IFind<IElement> within(ILocatorScope<IElement> scope) {
        Objects.requireNonNull(scope, "scope");
        return new PlaywrightFind(
                engine, context, PlaywrightScopeResolver.append(pendingScopes, scope));
    }

    @Override
    public ILocator<IElement> element() {
        return locator(LocatorDefinition.element());
    }

    @Override
    public ILocator<IElement> role(ElementRole role) {
        return locator(LocatorDefinition.forRole(role));
    }

    @Override
    public ILocator<IElement> link() {
        return role(ElementRole.LINK);
    }

    @Override
    public ILocator<IElement> button() {
        return role(ElementRole.BUTTON);
    }

    @Override
    public ILocator<IElement> textbox() {
        return role(ElementRole.TEXTBOX);
    }

    @Override
    public ILocator<IElement> searchbox() {
        return role(ElementRole.SEARCHBOX);
    }

    @Override
    public ILocator<IElement> checkbox() {
        return role(ElementRole.CHECKBOX);
    }

    @Override
    public ILocator<IElement> radio() {
        return role(ElementRole.RADIO);
    }

    @Override
    public ILocator<IElement> select() {
        return role(ElementRole.SELECT);
    }

    @Override
    public ILocator<IElement> option() {
        return role(ElementRole.OPTION);
    }

    @Override
    public ILocator<IElement> heading() {
        return role(ElementRole.HEADING);
    }

    @Override
    public ILocator<IElement> form() {
        return role(ElementRole.FORM);
    }

    @Override
    public ILocator<IElement> table() {
        return role(ElementRole.TABLE);
    }

    @Override
    public ILocator<IElement> list() {
        return role(ElementRole.LIST);
    }

    @Override
    public ILocator<IElement> image() {
        return role(ElementRole.IMAGE);
    }

    @Override
    public ILocator<IElement> banner() {
        return role(ElementRole.BANNER);
    }

    @Override
    public ILocator<IElement> navigation() {
        return role(ElementRole.NAVIGATION);
    }

    @Override
    public ILocator<IElement> main() {
        return role(ElementRole.MAIN);
    }

    @Override
    public ILocator<IElement> search() {
        return role(ElementRole.SEARCH);
    }

    @Override
    public ILocator<IElement> region() {
        return role(ElementRole.REGION);
    }

    @Override
    public ILocator<IElement> complementary() {
        return role(ElementRole.COMPLEMENTARY);
    }

    @Override
    public ILocator<IElement> contentInfo() {
        return role(ElementRole.CONTENTINFO);
    }

    @Override
    public ILocator<IElement> placeholder(String text) {
        return locator(
                LocatorDefinition.element().withPlaceholder(TextMatch.exactIgnoringCase(text)));
    }

    @Override
    public ILocator<IElement> text(String text) {
        return locator(
                LocatorDefinition.element().withVisibleText(TextMatch.exactIgnoringCase(text)));
    }

    @Override
    public ILocator<IElement> title(String text) {
        return locator(LocatorDefinition.element().withTitle(TextMatch.exactIgnoringCase(text)));
    }

    @Override
    public ILocator<IElement> altText(String text) {
        return locator(LocatorDefinition.element().withAltText(TextMatch.exactIgnoringCase(text)));
    }

    @Override
    public ILocator<IElement> id(String id) {
        return locator(LocatorDefinition.element().withId(id));
    }

    @Override
    public ILocator<IElement> nameAttribute(String name) {
        return locator(LocatorDefinition.element().withNameAttribute(name));
    }

    @Override
    public ILocator<IElement> attribute(String name, String value) {
        return locator(LocatorDefinition.element().withAttribute(name, value));
    }

    @Override
    public ILocator<IElement> testId(String value) {
        return locator(LocatorDefinition.element().withTestId(value));
    }

    @Override
    public ILocator<IElement> css(String selector) {
        return locator(LocatorDefinition.css(selector));
    }

    @Override
    public ILocator<IElement> xpath(String expression) {
        return locator(LocatorDefinition.xpath(expression));
    }

    private ILocator<IElement> locator(LocatorDefinition definition) {
        return new PlaywrightLocator(engine, context, pendingScopes, definition);
    }
}
