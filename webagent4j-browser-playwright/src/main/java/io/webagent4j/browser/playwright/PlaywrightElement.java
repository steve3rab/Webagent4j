package io.webagent4j.browser.playwright;

import com.microsoft.playwright.ElementHandle;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.TimeoutError;
import io.webagent4j.common.LocatorException;
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

/**
 * Internal live element adapter; queries are re-resolved at each fluent terminal operation.
 *
 * <p>Backed by exactly one of two Playwright primitives, chosen at construction:
 *
 * <ul>
 *   <li>a re-resolving {@link Locator} (the default) - every operation re-queries the live DOM by
 *       selector, so an equivalent element recreated after the original one is removed and replaced
 *       is picked up transparently;
 *   <li>a fixed {@link ElementHandle} - captured once, immediately upon discovery, for a leaf
 *       element found within a structured-scope container (see {@link
 *       PlaywrightLocatorBackend#resolveUniqueContainer}). A structured-scope container cannot be
 *       re-found later by a native Playwright selector without either a persistent DOM stamp or a
 *       position-based index, both unsafe against a later sibling insertion or reorder; capturing
 *       the leaf's handle at the moment it is discovered - before any such later mutation can occur
 *       - fixes its physical identity in a way a later reorder cannot perturb, and that only the
 *       node's own actual removal invalidates.
 * </ul>
 */
final class PlaywrightElement implements IElement {

    private final Locator locator;
    private final ElementHandle handle;
    private final ElementRole knownRole;
    private final PlaywrightLocatorBackend locatorBackend;
    private final LocatorScope originatingScope;
    private final LocatorConfig locatorConfig;
    private final double inspectionTimeoutMillis;
    private final Runnable scopeIdentityValidator;
    private final boolean structuredScopeContainer;

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
        this(
                locator,
                knownRole,
                locatorBackend,
                originatingScope,
                locatorConfig,
                inspectionTimeoutMillis,
                scopeIdentityValidator,
                false);
    }

    /**
     * Used only by {@link PlaywrightLocatorBackend#resolveUniqueContainer} for a container root.
     */
    PlaywrightElement(
            Locator locator,
            ElementRole knownRole,
            PlaywrightLocatorBackend locatorBackend,
            LocatorScope originatingScope,
            LocatorConfig locatorConfig,
            double inspectionTimeoutMillis,
            Runnable scopeIdentityValidator,
            boolean structuredScopeContainer) {
        this.locator = locator;
        this.handle = null;
        this.knownRole = knownRole;
        this.locatorBackend = locatorBackend;
        this.originatingScope = originatingScope;
        this.locatorConfig = locatorConfig;
        this.inspectionTimeoutMillis = inspectionTimeoutMillis;
        this.scopeIdentityValidator = scopeIdentityValidator;
        this.structuredScopeContainer = structuredScopeContainer;
    }

    /** A leaf element found within a structured-scope container, frozen to its physical handle. */
    PlaywrightElement(
            ElementHandle handle,
            ElementRole knownRole,
            PlaywrightLocatorBackend locatorBackend,
            LocatorScope originatingScope,
            LocatorConfig locatorConfig,
            double inspectionTimeoutMillis,
            Runnable scopeIdentityValidator) {
        this.locator = null;
        this.handle = handle;
        this.knownRole = knownRole;
        this.locatorBackend = locatorBackend;
        this.originatingScope = originatingScope;
        this.locatorConfig = locatorConfig;
        this.inspectionTimeoutMillis = inspectionTimeoutMillis;
        this.scopeIdentityValidator = scopeIdentityValidator;
        this.structuredScopeContainer = false;
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
        if (isAbsent()) {
            return detachedState();
        }
        Object inspected = evaluateOrNull(PlaywrightDomInspectionScripts.STATE_FUNCTION);
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
        com.microsoft.playwright.options.BoundingBox box =
                handle != null ? handle.boundingBox() : locator.boundingBox();
        if (box == null) {
            return Optional.empty();
        }
        return Optional.of(new BoundingBox(box.x, box.y, box.width, box.height));
    }

    @Override
    public void click() {
        validateScopeIdentity();
        if (handle != null) {
            handle.click();
        } else {
            locator.click();
        }
    }

    @Override
    public IFind<IElement> find() {
        return locatorBackend.findWithin(this, originatingScope, locatorConfig);
    }

    Locator locator() {
        validateScopeIdentity();
        return requireLocator();
    }

    Locator locatorWithoutScopeValidation() {
        return requireLocator();
    }

    private Locator requireLocator() {
        if (locator == null) {
            throw new LocatorException(
                    "This element was resolved as a fixed handle and cannot be used as a chainable"
                            + " scope root");
        }
        return locator;
    }

    void validateScopeIdentity() {
        if (scopeIdentityValidator != null) {
            scopeIdentityValidator.run();
        }
    }

    boolean isStructuredScopeContainer() {
        return structuredScopeContainer;
    }

    /**
     * Returns whether this element is currently absent (detached/removed), without starting any
     * hidden Playwright wait: {@code Locator#count()} never waits, and {@code element.isConnected}
     * evaluated directly against an already-resolved handle never waits either.
     */
    private boolean isAbsent() {
        if (handle != null) {
            try {
                return !Boolean.TRUE.equals(handle.evaluate("element => element.isConnected"));
            } catch (PlaywrightException failure) {
                return PlaywrightFailureClassifier.isFrameUnavailable(failure);
            }
        }
        try {
            return locator.count() == 0;
        } catch (TimeoutError failure) {
            return absentAfter(failure);
        } catch (PlaywrightException failure) {
            if (PlaywrightFailureClassifier.isFrameUnavailable(failure)) {
                return true;
            }
            throw failure;
        }
    }

    private Object evaluateOrNull(String expression) {
        try {
            if (handle != null) {
                return handle.evaluate(expression);
            }
            return locator.evaluate(
                    expression,
                    null,
                    new Locator.EvaluateOptions()
                            .setTimeout(
                                    PlaywrightLocatorBackend.requirePositivePlaywrightTimeout(
                                            inspectionTimeoutMillis, "Element inspection")));
        } catch (TimeoutError failure) {
            if (absentAfter(failure)) {
                return null;
            }
            throw failure;
        } catch (PlaywrightException failure) {
            if (PlaywrightFailureClassifier.isFrameUnavailable(failure)) {
                return null;
            }
            throw failure;
        }
    }

    /** Returns true only when a fresh synchronous recheck proves the timed-out target is gone. */
    private boolean absentAfter(TimeoutError originalFailure) {
        try {
            return handle != null
                    ? !Boolean.TRUE.equals(handle.evaluate("element => element.isConnected"))
                    : locator.count() == 0;
        } catch (RuntimeException recheckFailure) {
            originalFailure.addSuppressed(recheckFailure);
            throw originalFailure;
        }
    }

    private static boolean booleanValue(Map<String, Object> values, String name) {
        return Boolean.TRUE.equals(values.get(name));
    }

    private static ElementState detachedState() {
        return new ElementState(
                false, false, false, false, false, false, false, false, false, false, false, true);
    }
}
