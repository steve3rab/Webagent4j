package io.webagent4j.browser.playwright;

import com.microsoft.playwright.ElementHandle;
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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Internal live element adapter. Every element stays backed by a Playwright {@link Locator}, so it
 * can always be reused as a chainable scope root and re-resolved against the live DOM.
 *
 * <p>Read-only inspection first resolves the locator with {@link Locator#elementHandles()}, which
 * is a current-DOM query and does not start the locator wait used by {@link Locator#evaluate}. The
 * actual inspection then runs on the one already-resolved physical {@link ElementHandle}. This is
 * important for custom selector engines registered in an isolated content-script world: the
 * selector is resolved exactly once in its own world and is not re-resolved through {@link
 * Locator#evaluateAll(String)} in the main world.
 */
final class PlaywrightElement implements IElement, AutoCloseable {

    private final Locator locator;
    private final ElementRole knownRole;
    private final PlaywrightLocatorBackend locatorBackend;
    private final LocatorScope originatingScope;
    private final LocatorConfig locatorConfig;
    private final Runnable scopeIdentityValidator;
    private final String capturedIdentity;
    private final ElementHandle verifiedHandle;

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
                null,
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
                null,
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
                null);
    }

    /**
     * @param capturedIdentity the stable per-physical-node identity token captured at the moment
     *     this handle was resolved, or {@code null} when no such token was captured (in which case
     *     {@link #isStillTheOriginallyResolvedTarget()} conservatively returns {@code true}, since
     *     there is nothing to disprove it against).
     */
    PlaywrightElement(
            Locator locator,
            ElementRole knownRole,
            PlaywrightLocatorBackend locatorBackend,
            LocatorScope originatingScope,
            LocatorConfig locatorConfig,
            double inspectionTimeoutMillis,
            Runnable scopeIdentityValidator,
            String capturedIdentity) {
        if (Double.isNaN(inspectionTimeoutMillis) || inspectionTimeoutMillis < 0.0) {
            throw new IllegalArgumentException("inspectionTimeoutMillis must not be negative");
        }
        this.locator = locator;
        this.knownRole = knownRole;
        this.locatorBackend = locatorBackend;
        this.originatingScope = originatingScope;
        this.locatorConfig = locatorConfig;
        this.scopeIdentityValidator = scopeIdentityValidator;
        this.capturedIdentity = capturedIdentity;
        this.verifiedHandle = null;
    }

    /**
     * Copy constructor used only by {@link #verifiedForExecution()} to attach an already-verified,
     * still-open {@link ElementHandle} to an otherwise-identical view of {@code source}, so a
     * caller's very next native operation can act on precisely the physical node identity was just
     * reproven against - never on a second, independently re-resolved lookup.
     */
    private PlaywrightElement(PlaywrightElement source, ElementHandle verifiedHandle) {
        this.locator = source.locator;
        this.knownRole = source.knownRole;
        this.locatorBackend = source.locatorBackend;
        this.originatingScope = source.originatingScope;
        this.locatorConfig = source.locatorConfig;
        this.scopeIdentityValidator = source.scopeIdentityValidator;
        this.capturedIdentity = source.capturedIdentity;
        this.verifiedHandle = verifiedHandle;
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

    /**
     * Re-inspects the live DOM through this handle's own locator and compares the result against
     * {@link #capturedIdentity}. Never throws: any inability to prove identity - the node is gone,
     * the locator has become ambiguous, the identity bridge is unavailable, a document boundary was
     * crossed, or the fresh identity simply differs - is uniformly "not proven," so the caller's
     * fail-closed contract needs only this one boolean.
     */
    @Override
    public boolean isStillTheOriginallyResolvedTarget() {
        if (capturedIdentity == null) {
            return true;
        }
        try {
            Map<String, Object> freshIdentity = PlaywrightLocatorBackend.identifyOrNull(locator);
            if (freshIdentity == null) {
                return false;
            }
            return capturedIdentity.equals(freshIdentity.get("identity"));
        } catch (RuntimeException inconclusive) {
            return false;
        }
    }

    /**
     * Atomically re-verifies {@link #capturedIdentity} and, only when it is reproven, returns a
     * view of this exact same element carrying the already-verified, still-open {@link
     * ElementHandle} that proved it - never a second, independent {@link Locator} resolution a
     * caller might otherwise use for the actual native operation. This is the fix for the residual
     * gap {@link #isStillTheOriginallyResolvedTarget()} cannot close on its own: that method's
     * boolean answer and a subsequent, separately-resolved native call are still two distinct
     * lookups, so between them the live DOM could still substitute a different node satisfying the
     * same locator.
     *
     * <p>Returns {@link Optional#empty()} whenever identity cannot be reproven - detached,
     * replaced, ambiguous, or any inspection failure - uniformly "not proven," exactly like {@link
     * #isStillTheOriginallyResolvedTarget()}. Returns the unchanged {@code this} (no verified
     * handle attached) when no identity was ever captured for this element, since there is nothing
     * to disprove it against; a caller then falls back to its ordinary re-resolving path with no
     * added cost, preserving this backend's existing best-effort behavior for elements that never
     * opted into identity tracking.
     */
    @Override
    public Optional<IElement> verifiedForExecution() {
        validateScopeIdentity();
        if (capturedIdentity == null) {
            return Optional.of(this);
        }
        PlaywrightLocatorBackend.VerifiedHandle verified;
        try {
            verified =
                    PlaywrightLocatorBackend.resolveVerifiedHandleOrNull(locator, capturedIdentity);
        } catch (RuntimeException inconclusive) {
            return Optional.empty();
        }
        if (verified == null) {
            return Optional.empty();
        }
        return Optional.of(new PlaywrightElement(this, verified.handle()));
    }

    /**
     * Returns the already-verified, still-open {@link ElementHandle} attached by {@link
     * #verifiedForExecution()}, or {@link Optional#empty()} for every ordinary element - only a
     * view returned by that method ever carries one. A backend consumes this instead of {@link
     * #locator()} to perform its native operation on precisely the physical node whose identity was
     * just proven, rather than triggering another, independently re-resolved lookup.
     */
    Optional<ElementHandle> verifiedHandle() {
        return Optional.ofNullable(verifiedHandle);
    }

    /**
     * Disposes this view's attached verified handle, if any. Safe to call on every {@link
     * PlaywrightElement}: a no-op unless {@link #verifiedForExecution()} produced this instance.
     */
    @Override
    public void close() {
        if (verifiedHandle != null) {
            try {
                verifiedHandle.dispose();
            } catch (PlaywrightException ignored) {
                // Best-effort cleanup only. Never replace the semantic result of the caller's
                // action.
            }
        }
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

    private Object evaluateWithoutAdditionalValidation(String elementFunction) {
        List<ElementHandle> handles = List.of();
        try {
            handles = locator.elementHandles();
            if (handles.isEmpty()) {
                return null;
            }
            if (handles.size() > 1) {
                throw new AmbiguousLocatorException(
                        "Live element became ambiguous during current-DOM inspection");
            }
            return handles.getFirst().evaluate(elementFunction, null);
        } catch (PlaywrightException failure) {
            if (PlaywrightFailureClassifier.isFrameUnavailable(failure)) {
                return null;
            }
            if (PlaywrightFailureClassifier.isDifferentDocumentAdoptionRace(failure)
                    && confirmedAbsent(locator, failure)) {
                return null;
            }
            throw failure;
        } finally {
            dispose(handles);
        }
    }

    /**
     * Converts a cross-document handle-adoption race to absence only after a fresh synchronous
     * recheck proves that the live locator is now gone. A still-present element or any opaque
     * recheck failure preserves the original Playwright failure.
     */
    private static boolean confirmedAbsent(Locator locator, PlaywrightException original) {
        try {
            return locator.count() == 0;
        } catch (PlaywrightException recheckFailure) {
            if (PlaywrightFailureClassifier.isFrameUnavailable(recheckFailure)) {
                return true;
            }
            original.addSuppressed(recheckFailure);
            throw original;
        } catch (RuntimeException recheckFailure) {
            original.addSuppressed(recheckFailure);
            throw original;
        }
    }

    private static void dispose(List<ElementHandle> handles) {
        for (ElementHandle handle : handles) {
            try {
                handle.dispose();
            } catch (PlaywrightException ignored) {
                // Best-effort cleanup only. Never replace the semantic result/failure of the probe.
            }
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
