package io.webagent4j.locator.api;

/** Entry point for semantic and selector-based element queries. */
public interface IFind<E> {
    /** Starts an unconstrained semantic query. */
    ILocator<E> element();

    /** Starts a query constrained to one semantic role. */
    ILocator<E> role(ElementRole role);

    /** Locates links by semantic role. */
    ILocator<E> link();

    /** Locates buttons by semantic role. */
    ILocator<E> button();

    /** Locates text inputs and text areas by semantic role. */
    ILocator<E> textbox();

    /** Locates search inputs by their implicit or explicit searchbox role. */
    ILocator<E> searchbox();

    /** Locates checkboxes by semantic role. */
    ILocator<E> checkbox();

    /** Locates radio buttons by semantic role. */
    ILocator<E> radio();

    /** Locates select controls by semantic role. */
    ILocator<E> select();

    /** Locates options within select and listbox controls. */
    ILocator<E> option();

    /** Locates headings by semantic role. */
    ILocator<E> heading();

    /** Locates forms by semantic role. */
    ILocator<E> form();

    /** Locates tables by semantic role. */
    ILocator<E> table();

    /** Locates lists by semantic role. */
    ILocator<E> list();

    /** Locates images by semantic role. */
    ILocator<E> image();

    /** Locates page banner landmarks. */
    ILocator<E> banner();

    /** Locates navigation landmarks. */
    ILocator<E> navigation();

    /** Locates the main landmark. */
    ILocator<E> main();

    /** Locates search landmarks. */
    ILocator<E> search();

    /** Locates named region landmarks. */
    ILocator<E> region();

    /** Locates complementary landmarks. */
    ILocator<E> complementary();

    /** Locates content information landmarks. */
    ILocator<E> contentInfo();

    /** Locates elements by exact placeholder text. */
    ILocator<E> placeholder(String text);

    /** Locates elements by exact visible text. */
    ILocator<E> text(String text);

    /** Locates elements by exact title text. */
    ILocator<E> title(String text);

    /** Locates images and similar elements by exact alternative text. */
    ILocator<E> altText(String text);

    /** Locates an element by exact id. */
    ILocator<E> id(String id);

    /** Locates elements by exact HTML name attribute. */
    ILocator<E> nameAttribute(String name);

    /** Locates elements by an exact custom attribute. */
    ILocator<E> attribute(String name, String value);

    /** Locates elements by exact data-testid value. */
    ILocator<E> testId(String value);

    /** Locates elements using a CSS selector as an explicit escape hatch. */
    ILocator<E> css(String selector);

    /** Locates elements using an XPath expression as an explicit escape hatch. */
    ILocator<E> xpath(String expression);
}
