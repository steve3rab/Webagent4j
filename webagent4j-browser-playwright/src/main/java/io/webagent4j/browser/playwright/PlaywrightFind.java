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
 * <p>Neither {@link #within(IElement)} nor {@link #within(ILocatorScope)} resolves anything
 * immediately: each appends one {@link IPendingScope} to a single ordered chain, so a mixed chain
 * of explicit-element and structured scopes is resolved in exactly the order it was declared - see
 * {@link PlaywrightScopeResolver#resolvePendingScopes} - never regrouped by scope kind. A
 * structured scope's definition is additionally re-resolved fresh at every terminal operation - see
 * {@link PlaywrightLocator} - so a semantic region such as "the section labelled Shipping" is never
 * frozen into a single DOM node captured once at chain-build time.
 */
final class PlaywrightFind implements IFind<IElement> {

    private final ILocatorEngine engine;
    private final LocatorContext baseContext;
    private final List<IPendingScope> pendingScopes;

    PlaywrightFind(ILocatorEngine engine, LocatorContext baseContext) {
        this(engine, baseContext, List.of());
    }

    private PlaywrightFind(
            ILocatorEngine engine, LocatorContext baseContext, List<IPendingScope> pendingScopes) {
        this.engine = engine;
        this.baseContext = baseContext;
        this.pendingScopes = pendingScopes;
    }

    @Override
    public IFind<IElement> within(IElement scope) {
        Objects.requireNonNull(scope, "scope");
        return new PlaywrightFind(
                engine,
                baseContext,
                PlaywrightScopeResolver.append(pendingScopes, new IPendingScope.Element(scope)));
    }

    @Override
    public IFind<IElement> within(ILocatorScope<IElement> scope) {
        Objects.requireNonNull(scope, "scope");
        return new PlaywrightFind(
                engine,
                baseContext,
                PlaywrightScopeResolver.append(pendingScopes, new IPendingScope.Structured(scope)));
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
        return new PlaywrightLocator(engine, baseContext, pendingScopes, definition);
    }
}
