package io.webagent4j.browser.playwright;

import com.microsoft.playwright.Locator;
import io.webagent4j.dom.BoundingBox;
import io.webagent4j.dom.ElementState;
import io.webagent4j.dom.IElement;
import io.webagent4j.locator.LocatorConfig;
import io.webagent4j.locator.LocatorScope;
import io.webagent4j.locator.api.ElementRole;
import io.webagent4j.locator.api.IFind;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** Internal live element adapter; queries are re-resolved at each fluent terminal operation. */
final class PlaywrightElement implements IElement {

    private final Locator locator;
    private final ElementRole knownRole;
    private final PlaywrightLocatorBackend locatorBackend;
    private final LocatorScope originatingScope;
    private final LocatorConfig locatorConfig;

    PlaywrightElement(
            Locator locator,
            ElementRole knownRole,
            PlaywrightLocatorBackend locatorBackend,
            LocatorScope originatingScope,
            LocatorConfig locatorConfig) {
        this.locator = locator;
        this.knownRole = knownRole;
        this.locatorBackend = locatorBackend;
        this.originatingScope = originatingScope;
        this.locatorConfig = locatorConfig;
    }

    @Override
    public ElementRole role() {
        if (knownRole != ElementRole.UNKNOWN) {
            return knownRole;
        }
        String raw = String.valueOf(locator.evaluate(PlaywrightDomInspectionScripts.ROLE_FUNCTION));
        return switch (raw.toLowerCase(Locale.ROOT)) {
            case "a", "link" -> ElementRole.LINK;
            case "button" -> ElementRole.BUTTON;
            case "input", "textarea", "textbox" -> ElementRole.TEXTBOX;
            case "searchbox" -> ElementRole.SEARCHBOX;
            case "checkbox" -> ElementRole.CHECKBOX;
            case "radio" -> ElementRole.RADIO;
            case "select", "combobox" -> ElementRole.SELECT;
            case "option" -> ElementRole.OPTION;
            case "slider" -> ElementRole.SLIDER;
            case "spinbutton" -> ElementRole.SPINBUTTON;
            case "switch" -> ElementRole.SWITCH;
            case "img" -> ElementRole.IMAGE;
            case "header", "banner" -> ElementRole.BANNER;
            case "nav", "navigation" -> ElementRole.NAVIGATION;
            case "main" -> ElementRole.MAIN;
            case "search" -> ElementRole.SEARCH;
            case "region" -> ElementRole.REGION;
            case "aside", "complementary" -> ElementRole.COMPLEMENTARY;
            case "footer", "contentinfo" -> ElementRole.CONTENTINFO;
            case "form" -> ElementRole.FORM;
            case "table" -> ElementRole.TABLE;
            case "ul", "ol", "list" -> ElementRole.LIST;
            case "dialog" -> ElementRole.DIALOG;
            case "alertdialog" -> ElementRole.ALERTDIALOG;
            case "alert" -> ElementRole.ALERT;
            case "status" -> ElementRole.STATUS;
            case "menu" -> ElementRole.MENU;
            case "menubar" -> ElementRole.MENUBAR;
            case "menuitem" -> ElementRole.MENUITEM;
            case "tab" -> ElementRole.TAB;
            case "tablist" -> ElementRole.TABLIST;
            case "tabpanel" -> ElementRole.TABPANEL;
            case "grid" -> ElementRole.GRID;
            default -> raw.matches("h[1-6]") ? ElementRole.HEADING : ElementRole.UNKNOWN;
        };
    }

    @Override
    public String accessibleName() {
        Object value = locator.evaluate(PlaywrightDomInspectionScripts.ACCESSIBLE_NAME_FUNCTION);
        return value == null ? "" : String.valueOf(value);
    }

    @Override
    public String text() {
        String value = locator.textContent();
        return value == null ? "" : value.trim().replaceAll("\\s+", " ");
    }

    @Override
    public String tagName() {
        return String.valueOf(locator.evaluate("element => element.tagName.toLowerCase()"));
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, String> attributes() {
        Map<String, Object> raw =
                (Map<String, Object>)
                        locator.evaluate(
                                "element => Object.fromEntries(Array.from(element.attributes)"
                                        + ".map(attribute => [attribute.name, attribute.value]))");
        Map<String, String> attributes = new LinkedHashMap<>();
        raw.forEach((name, value) -> attributes.put(name, String.valueOf(value)));
        return Map.copyOf(attributes);
    }

    @Override
    public String value() {
        Object value = locator.evaluate("element => 'value' in element ? element.value : ''");
        return value == null ? "" : String.valueOf(value);
    }

    @Override
    public boolean visible() {
        return state().visible();
    }

    @Override
    public boolean enabled() {
        return state().enabled();
    }

    @Override
    @SuppressWarnings("unchecked")
    public ElementState state() {
        if (locator.count() == 0) {
            return detachedState();
        }
        try {
            Map<String, Object> value =
                    (Map<String, Object>)
                            locator.evaluate(PlaywrightDomInspectionScripts.STATE_FUNCTION);
            return new ElementState(
                    booleanValue(value, "present"),
                    booleanValue(value, "visible"),
                    booleanValue(value, "enabled"),
                    booleanValue(value, "editable"),
                    booleanValue(value, "readOnly"),
                    booleanValue(value, "checked"),
                    booleanValue(value, "selected"),
                    booleanValue(value, "focused"),
                    booleanValue(value, "inViewport"),
                    booleanValue(value, "clickable"),
                    booleanValue(value, "covered"),
                    true);
        } catch (com.microsoft.playwright.PlaywrightException detached) {
            return detachedState();
        }
    }

    @Override
    public Optional<BoundingBox> boundingBox() {
        com.microsoft.playwright.options.BoundingBox box = locator.boundingBox();
        if (box == null) {
            return Optional.empty();
        }
        return Optional.of(new BoundingBox(box.x, box.y, box.width, box.height));
    }

    @Override
    public void click() {
        locator.click();
    }

    @Override
    public IFind<IElement> find() {
        return locatorBackend.findWithin(this, originatingScope, locatorConfig);
    }

    Locator locator() {
        return locator;
    }

    private static boolean booleanValue(Map<String, Object> values, String name) {
        return Boolean.TRUE.equals(values.get(name));
    }

    private static ElementState detachedState() {
        return new ElementState(
                false, false, false, false, false, false, false, false, false, false, false, true);
    }
}
