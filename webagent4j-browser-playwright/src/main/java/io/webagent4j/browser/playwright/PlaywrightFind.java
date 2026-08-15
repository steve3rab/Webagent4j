package io.webagent4j.browser.playwright;

import io.webagent4j.browser.InteractionContext;
import io.webagent4j.dom.IElement;
import io.webagent4j.locator.ILocatorEngine;
import io.webagent4j.locator.LocatorContext;
import io.webagent4j.locator.api.ElementRole;
import io.webagent4j.locator.api.IFind;
import io.webagent4j.locator.api.ILocator;
import io.webagent4j.locator.api.LocatorDefinition;
import io.webagent4j.locator.api.TextMatch;

/** Internal fluent entry point delegating every terminal operation to the shared locator engine. */
final class PlaywrightFind implements IFind<IElement> {

    private final ILocatorEngine engine;
    private final LocatorContext context;

    PlaywrightFind(ILocatorEngine engine, LocatorContext context) {
        this.engine = engine;
        this.context = context;
    }

    @Override
    public IFind<IElement> within(Object scope) {
        return new PlaywrightFind(engine, resolveScope(scope));
    }

    @Override
    public IFind<IElement> inContext(Object context) {
        return within(context);
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
        return new PlaywrightLocator(engine, context, definition);
    }

    private LocatorContext resolveScope(Object scope) {
        if (scope instanceof IElement element) {
            return context.within(element);
        }
        if (scope instanceof InteractionContext interactionContext) {
            LocatorContext next = context;
            if (interactionContext.scope().isPresent()) {
                next = next.within(interactionContext.scope().get());
            }
            for (String text : interactionContext.containingText()) {
                if (text == null || text.isBlank()) {
                    continue;
                }
                // Prefer accessible name matching (aria-label, aria-labelledby, etc.) and fall back to
                // visible text when accessible name does not match anything.
                IElement container;
                try {
                    container =
                            engine.locate(
                                            next,
                                            LocatorDefinition.element()
                                                    .withAccessibleName(TextMatch.exactIgnoringCase(text)))
                                    .element();
                } catch (RuntimeException accessibleFailure) {
                    // Try visible text as a fallback before giving up
                    container =
                            engine.locate(
                                            next,
                                            LocatorDefinition.element()
                                                    .withVisibleText(TextMatch.exactIgnoringCase(text)))
                                    .element();
                }
                next = next.within(container);
                break;
            }
            return next;
        }
        throw new IllegalArgumentException("scope must be an element or InteractionContext");
    }
}
