package io.webagent4j.browser.playwright;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.PlaywrightException;
import io.webagent4j.dom.BoundingBox;
import io.webagent4j.dom.ElementState;
import io.webagent4j.dom.IElement;
import io.webagent4j.locator.AmbiguousLocatorException;
import io.webagent4j.locator.LocatorConfig;
import io.webagent4j.locator.LocatorScope;
import io.webagent4j.locator.api.ElementRole;
import io.webagent4j.locator.api.IFind;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Internal live element adapter. Every element stays backed by a Playwright {@link Locator}, so it
 * can always be reused as a chainable scope root and re-resolved against the live DOM.
 *
 * <p>Read-only element inspection deliberately uses {@link Locator#evaluateAll(String)} instead of
 * {@link Locator#evaluate(String)}. {@code evaluateAll()} inspects the locator's current matches
 * without starting a nested Playwright wait for the element to appear. This keeps deadline-edge
 * locator resolution deterministic when only a sub-millisecond caller budget remains.
 */
final class PlaywrightElement implements IElement {

    private final Locator locator;
    private final ElementRole knownRole;
    private final PlaywrightLocatorBackend locatorBackend;
    private final LocatorScope originatingScope;
    private final LocatorConfig locatorConfig;
    private final Runnable scopeIdentityValidator;

    PlaywrightElement(
            Locator locator,
            ElementRole knownRole,
            PlaywrightLocatorBackend locatorBackend,
            LocatorScope originatingScope,
            LocatorConfig locatorConfig) {
        this(
                locator,
                knownRole,
                locatorBackend,
                originatingScope,
                locatorConfig,
                PlaywrightLocatorBackend.inspectionTimeoutMillis(locatorConfig.defaultTimeout(), 1),
                null);
    }

    PlaywrightElement(
            Locator locator,
            ElementRole knownRole,
            PlaywrightLocatorBackend locatorBackend,
            LocatorScope originatingScope,
            LocatorConfig locatorConfig,
            double inspectionTimeoutMillis) {
        this(
                locator,
                knownRole,
                locatorBackend,
                originatingScope,
                locatorConfig,
                inspectionTimeoutMillis,
                null);
    }

    PlaywrightElement(
            Locator locator,
            ElementRole knownRole,
            PlaywrightLocatorBackend locatorBackend,
            LocatorScope originatingScope,
            LocatorConfig locatorConfig,
            double inspectionTimeoutMillis,
            Runnable scopeIdentityValidator) {
        if (Double.isNaN(inspectionTimeoutMillis) || inspectionTimeoutMillis < 0.0) {
            throw new IllegalArgumentException("inspectionTimeoutMillis must not be negative");
        }
        this.locator = locator;
        this.knownRole = knownRole;
        this.locatorBackend = locatorBackend;
        this.originatingScope = originatingScope;
        this.locatorConfig = locatorConfig;
        this.scopeIdentityValidator = scopeIdentityValidator;
    }

    @Override
    public ElementRole role() {
        if (knownRole != ElementRole.UNKNOWN) {
            return knownRole;
        }
        Object inspected = evaluateOrNull(PlaywrightDomInspectionScripts.ROLE_FUNCTION);
        if (inspected == null) {
            return ElementRole.UNKNOWN;
        }
        String raw = String.valueOf(inspected);
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
        Object value = evaluateOrNull(PlaywrightDomInspectionScripts.ACCESSIBLE_NAME_FUNCTION);
        return value == null ? "" : String.valueOf(value);
    }

    boolean hasElementDescendant() {
        return Boolean.TRUE.equals(
                evaluateOrNull(PlaywrightDomInspectionScripts.HAS_ELEMENT_DESCENDANT_FUNCTION));
    }

    @Override
    public String text() {
        Object value = evaluateOrNull("element => element.textContent");
        return value == null ? "" : String.valueOf(value).trim().replaceAll("\\s+", " ");
    }

    @Override
    public String tagName() {
        Object value = evaluateOrNull("element => element.tagName.toLowerCase()");
        return value == null ? "" : String.valueOf(value);
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, String> attributes() {
        Map<String, Object> raw =
                (Map<String, Object>)
                        evaluateOrNull(
                                "element => Object.fromEntries(Array.from(element.attributes)"
                                        + ".map(attribute => [attribute.name, attribute.value]))");
        if (raw == null) {
            return Map.of();
        }
        Map<String, String> attributes = new LinkedHashMap<>();
        raw.forEach((name, value) -> attributes.put(name, String.valueOf(value)));
        return Map.copyOf(attributes);
    }

    @Override
    public String value() {
        Object value = evaluateOrNull("element => 'value' in element ? element.value : ''");
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
        validateScopeIdentity();
        Object inspected =
                evaluateWithoutAdditionalValidation(PlaywrightDomInspectionScripts.STATE_FUNCTION);
        if (inspected == null) {
            return detachedState();
        }
        Map<String, Object> value = (Map<String, Object>) inspected;
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
    }

    @Override
    public Optional<BoundingBox> boundingBox() {
        validateScopeIdentity();
        com.microsoft.playwright.options.BoundingBox box = locator.boundingBox();
        if (box == null) {
            return Optional.empty();
        }
        return Optional.of(new BoundingBox(box.x, box.y, box.width, box.height));
    }

    @Override
    public void click() {
        validateScopeIdentity();
        locator.click();
    }

    @Override
    public IFind<IElement> find() {
        return locatorBackend.findWithin(this, originatingScope, locatorConfig);
    }

    Locator locator() {
        validateScopeIdentity();
        return locator;
    }

    Locator locatorWithoutScopeValidation() {
        return locator;
    }

    void validateScopeIdentity() {
        if (scopeIdentityValidator != null) {
            scopeIdentityValidator.run();
        }
    }

    private Object evaluateOrNull(String elementFunction) {
        validateScopeIdentity();
        return evaluateWithoutAdditionalValidation(elementFunction);
    }

    @SuppressWarnings("unchecked")
    private Object evaluateWithoutAdditionalValidation(String elementFunction) {
        try {
            Map<String, Object> result =
                    (Map<String, Object>)
                            locator.evaluateAll(currentElementInspection(elementFunction));
            int count = ((Number) result.get("count")).intValue();
            if (count == 0) {
                return null;
            }
            if (count > 1) {
                throw new AmbiguousLocatorException(
                        "Live element became ambiguous during current-DOM inspection");
            }
            return result.get("value");
        } catch (PlaywrightException failure) {
            if (PlaywrightFailureClassifier.isFrameUnavailable(failure)) {
                return null;
            }
            throw failure;
        }
    }

    private static String currentElementInspection(String elementFunction) {
        return """
                elements => {
                  const count = elements.length;
                  if (count !== 1) {
                    return { count };
                  }
                  const inspect = (%s);
                  return { count, value: inspect(elements[0]) };
                }
                """
                .formatted(elementFunction);
    }

    private static boolean booleanValue(Map<String, Object> values, String name) {
        return Boolean.TRUE.equals(values.get(name));
    }

    private static ElementState detachedState() {
        return new ElementState(
                false, false, false, false, false, false, false, false, false, false, false, true);
    }
}
