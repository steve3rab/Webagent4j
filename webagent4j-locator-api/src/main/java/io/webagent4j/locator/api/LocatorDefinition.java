package io.webagent4j.locator.api;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable, backend-neutral and conceptually serializable element query.
 *
 * <p>A definition records intent only. It performs no browser work and is safe to share between
 * threads.
 *
 * @param role requested semantic role
 * @param accessibleName accessible-name criterion
 * @param label associated-label criterion
 * @param placeholder placeholder criterion
 * @param title title criterion
 * @param altText alternative-text criterion
 * @param visibleText visible-text criterion
 * @param id exact element id
 * @param nameAttribute exact HTML name attribute
 * @param attributes exact custom attributes
 * @param testId exact data-testid value
 * @param css explicit CSS selector escape hatch
 * @param xpath explicit XPath selector escape hatch
 * @param visible required visibility state
 * @param enabled required enabled state
 * @param editable required editable state
 * @param checked required checked state
 * @param selected required selected state
 * @param timeout resolution timeout override
 * @param waitUntilVisible whether resolution waits for a visible candidate
 * @param readOnly required read-only state
 * @param focused required focus state
 * @param inViewport required viewport state
 * @param clickable required reliable clickability state
 * @param covered required covered state
 * @param stability required continuous stability duration
 */
public record LocatorDefinition(
        Optional<ElementRole> role,
        Optional<TextMatch> accessibleName,
        Optional<TextMatch> label,
        Optional<TextMatch> placeholder,
        Optional<TextMatch> title,
        Optional<TextMatch> altText,
        Optional<TextMatch> visibleText,
        Optional<String> id,
        Optional<String> nameAttribute,
        Map<String, String> attributes,
        Optional<String> testId,
        Optional<String> css,
        Optional<String> xpath,
        Optional<Boolean> visible,
        Optional<Boolean> enabled,
        Optional<Boolean> editable,
        Optional<Boolean> checked,
        Optional<Boolean> selected,
        Optional<Duration> timeout,
        boolean waitUntilVisible,
        Optional<Boolean> readOnly,
        Optional<Boolean> focused,
        Optional<Boolean> inViewport,
        Optional<Boolean> clickable,
        Optional<Boolean> covered,
        Optional<Duration> stability) {

    /** Validates all immutable query components. */
    public LocatorDefinition {
        role = requireOptional(role, "role");
        accessibleName = requireOptional(accessibleName, "accessibleName");
        label = requireOptional(label, "label");
        placeholder = requireOptional(placeholder, "placeholder");
        title = requireOptional(title, "title");
        altText = requireOptional(altText, "altText");
        visibleText = requireOptional(visibleText, "visibleText");
        id = normalizeOptional(id, "id");
        nameAttribute = normalizeOptional(nameAttribute, "nameAttribute");
        testId = normalizeOptional(testId, "testId");
        css = normalizeOptional(css, "css");
        xpath = normalizeOptional(xpath, "xpath");
        visible = requireOptional(visible, "visible");
        enabled = requireOptional(enabled, "enabled");
        editable = requireOptional(editable, "editable");
        checked = requireOptional(checked, "checked");
        selected = requireOptional(selected, "selected");
        timeout = requireOptional(timeout, "timeout");
        timeout.ifPresent(value -> requirePositive(value, "timeout"));
        readOnly = requireOptional(readOnly, "readOnly");
        focused = requireOptional(focused, "focused");
        inViewport = requireOptional(inViewport, "inViewport");
        clickable = requireOptional(clickable, "clickable");
        covered = requireOptional(covered, "covered");
        stability = requireOptional(stability, "stability");
        stability.ifPresent(value -> requirePositive(value, "stability"));
        attributes = immutableAttributes(attributes);
        if (css.isPresent() && xpath.isPresent()) {
            throw new IllegalArgumentException("CSS and XPath cannot be combined");
        }
    }

    /** Creates an unconstrained semantic definition. */
    public static LocatorDefinition element() {
        return new LocatorDefinition(
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Map.of(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                false,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
    }

    /** Creates a role-based definition. */
    public static LocatorDefinition forRole(ElementRole role) {
        return element().withRole(role);
    }

    /** Returns a copy constrained to the supplied semantic role. */
    public LocatorDefinition role(ElementRole value) {
        return withRole(value);
    }

    /** Creates an explicit CSS selector definition. */
    public static LocatorDefinition css(String selector) {
        return element()
                .copy(
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        Optional.of(requireValue(selector, "selector")),
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null);
    }

    /** Creates an explicit XPath selector definition. */
    public static LocatorDefinition xpath(String expression) {
        return element()
                .copy(
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        Optional.of(requireValue(expression, "expression")),
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null);
    }

    /** Returns a copy constrained to the supplied semantic role. */
    public LocatorDefinition withRole(ElementRole value) {
        return copy(
                Optional.of(Objects.requireNonNull(value, "role")),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null);
    }

    /** Returns a copy constrained by an exact, case-insensitive accessible name. */
    public LocatorDefinition named(String value) {
        return withAccessibleName(TextMatch.exactIgnoringCase(value));
    }

    /** Returns a copy constrained by an accessible name containing the requested text. */
    public LocatorDefinition nameContaining(String value) {
        return withAccessibleName(TextMatch.containing(value));
    }

    /** Returns a copy constrained by a conservative fuzzy accessible-name match. */
    public LocatorDefinition fuzzyName(String value) {
        return withAccessibleName(TextMatch.fuzzy(value));
    }

    /** Returns a copy using the supplied accessible-name criterion. */
    public LocatorDefinition withAccessibleName(TextMatch value) {
        return copy(
                null,
                Optional.of(Objects.requireNonNull(value, "accessibleName")),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null);
    }

    /** Returns a copy constrained by an exact, case-insensitive associated label. */
    public LocatorDefinition labelled(String value) {
        return copy(
                null,
                null,
                Optional.of(TextMatch.exactIgnoringCase(value)),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null);
    }

    /** Returns a copy constrained by placeholder text. */
    public LocatorDefinition withPlaceholder(TextMatch value) {
        return copy(
                null,
                null,
                null,
                Optional.of(Objects.requireNonNull(value, "placeholder")),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null);
    }

    /** Returns a copy constrained by title text. */
    public LocatorDefinition withTitle(TextMatch value) {
        return copy(
                null,
                null,
                null,
                null,
                Optional.of(Objects.requireNonNull(value, "title")),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null);
    }

    /** Returns a copy constrained by alternative text. */
    public LocatorDefinition withAltText(TextMatch value) {
        return copy(
                null,
                null,
                null,
                null,
                null,
                Optional.of(Objects.requireNonNull(value, "altText")),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null);
    }

    /** Returns a copy constrained by visible text. */
    public LocatorDefinition withVisibleText(TextMatch value) {
        return copy(
                null,
                null,
                null,
                null,
                null,
                null,
                Optional.of(Objects.requireNonNull(value, "visibleText")),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null);
    }

    /** Returns a copy constrained to an exact id. */
    public LocatorDefinition withId(String value) {
        return copy(
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                Optional.of(requireValue(value, "id")),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null);
    }

    /** Returns a copy constrained to an exact HTML name attribute. */
    public LocatorDefinition withNameAttribute(String value) {
        return copy(
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                Optional.of(requireValue(value, "nameAttribute")),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null);
    }

    /** Returns a copy constrained to one exact custom attribute. */
    public LocatorDefinition withAttribute(String name, String value) {
        String requiredName = requireValue(name, "name");
        String requiredValue = requireValue(value, "value");
        Map<String, String> next = new LinkedHashMap<>(attributes);
        next.put(requiredName, requiredValue);
        return copy(
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                Map.copyOf(next),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null);
    }

    /** Returns a copy constrained to an exact data-testid value. */
    public LocatorDefinition withTestId(String value) {
        return copy(
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                Optional.of(requireValue(value, "testId")),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null);
    }

    /** Returns a copy requiring visible candidates. */
    public LocatorDefinition visibleOnly() {
        return copy(
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                Optional.of(true),
                null,
                null,
                null,
                null,
                null,
                null);
    }

    /** Returns a copy requiring hidden candidates. */
    public LocatorDefinition hiddenOnly() {
        return copy(
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                Optional.of(false),
                null,
                null,
                null,
                null,
                null,
                null);
    }

    /** Returns a copy requiring enabled candidates. */
    public LocatorDefinition enabledOnly() {
        return copy(
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                Optional.of(true),
                null,
                null,
                null,
                null,
                null);
    }

    /** Returns a copy requiring disabled candidates. */
    public LocatorDefinition disabledOnly() {
        return copy(
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                Optional.of(false),
                null,
                null,
                null,
                null,
                null);
    }

    /** Returns a copy requiring editable candidates. */
    public LocatorDefinition editableOnly() {
        return copy(
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                Optional.of(true),
                null,
                null,
                null,
                null);
    }

    /** Returns a copy requiring checked candidates. */
    public LocatorDefinition checkedOnly() {
        return copy(
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                Optional.of(true),
                null,
                null,
                null);
    }

    /** Returns a copy requiring selected candidates. */
    public LocatorDefinition selectedOnly() {
        return copy(
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                Optional.of(true),
                null,
                null);
    }

    /** Returns a copy requiring read-only candidates. */
    public LocatorDefinition readOnlyOnly() {
        return copyState(Optional.of(true), null, null, null, null, null);
    }

    /** Returns a copy requiring the focused candidate. */
    public LocatorDefinition focusedOnly() {
        return copyState(null, Optional.of(true), null, null, null, null);
    }

    /** Returns a copy requiring candidates inside the current viewport. */
    public LocatorDefinition inViewportOnly() {
        return copyState(null, null, Optional.of(true), null, null, null);
    }

    /** Returns a copy requiring candidates that can reliably receive a click. */
    public LocatorDefinition clickableOnly() {
        return copyState(null, null, null, Optional.of(true), null, null);
    }

    /** Returns a copy requiring candidates covered by another element. */
    public LocatorDefinition coveredOnly() {
        return copyState(null, null, null, null, Optional.of(true), null);
    }

    /** Returns a copy requiring continuous identity and state stability. */
    public LocatorDefinition stableFor(Duration value) {
        requirePositive(Objects.requireNonNull(value, "stability"), "stability");
        return copyState(null, null, null, null, null, Optional.of(value));
    }

    /** Returns a copy with a positive resolution timeout override. */
    public LocatorDefinition withTimeout(Duration value) {
        requirePositive(Objects.requireNonNull(value, "timeout"), "timeout");
        return copy(
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                Optional.of(value),
                null);
    }

    /** Returns a copy that waits until at least one visible candidate exists. */
    public LocatorDefinition waitingUntilVisible() {
        return copy(
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                Optional.of(true),
                null,
                null,
                null,
                null,
                null,
                true);
    }

    @SuppressWarnings("checkstyle:ParameterNumber")
    private LocatorDefinition copy(
            Optional<ElementRole> nextRole,
            Optional<TextMatch> nextAccessibleName,
            Optional<TextMatch> nextLabel,
            Optional<TextMatch> nextPlaceholder,
            Optional<TextMatch> nextTitle,
            Optional<TextMatch> nextAltText,
            Optional<TextMatch> nextVisibleText,
            Optional<String> nextId,
            Optional<String> nextNameAttribute,
            Map<String, String> nextAttributes,
            Optional<String> nextTestId,
            Optional<String> nextCss,
            Optional<String> nextXpath,
            Optional<Boolean> nextVisible,
            Optional<Boolean> nextEnabled,
            Optional<Boolean> nextEditable,
            Optional<Boolean> nextChecked,
            Optional<Boolean> nextSelected,
            Optional<Duration> nextTimeout,
            Boolean nextWaitUntilVisible) {
        return new LocatorDefinition(
                replacementOrCurrent(nextRole, role),
                replacementOrCurrent(nextAccessibleName, accessibleName),
                replacementOrCurrent(nextLabel, label),
                replacementOrCurrent(nextPlaceholder, placeholder),
                replacementOrCurrent(nextTitle, title),
                replacementOrCurrent(nextAltText, altText),
                replacementOrCurrent(nextVisibleText, visibleText),
                replacementOrCurrent(nextId, id),
                replacementOrCurrent(nextNameAttribute, nameAttribute),
                replacementOrCurrent(nextAttributes, attributes),
                replacementOrCurrent(nextTestId, testId),
                replacementOrCurrent(nextCss, css),
                replacementOrCurrent(nextXpath, xpath),
                replacementOrCurrent(nextVisible, visible),
                replacementOrCurrent(nextEnabled, enabled),
                replacementOrCurrent(nextEditable, editable),
                replacementOrCurrent(nextChecked, checked),
                replacementOrCurrent(nextSelected, selected),
                replacementOrCurrent(nextTimeout, timeout),
                replacementOrCurrent(nextWaitUntilVisible, waitUntilVisible),
                readOnly,
                focused,
                inViewport,
                clickable,
                covered,
                stability);
    }

    @SuppressWarnings("checkstyle:ParameterNumber")
    private LocatorDefinition copyState(
            Optional<Boolean> nextReadOnly,
            Optional<Boolean> nextFocused,
            Optional<Boolean> nextInViewport,
            Optional<Boolean> nextClickable,
            Optional<Boolean> nextCovered,
            Optional<Duration> nextStability) {
        return new LocatorDefinition(
                role,
                accessibleName,
                label,
                placeholder,
                title,
                altText,
                visibleText,
                id,
                nameAttribute,
                attributes,
                testId,
                css,
                xpath,
                visible,
                enabled,
                editable,
                checked,
                selected,
                timeout,
                waitUntilVisible,
                replacementOrCurrent(nextReadOnly, readOnly),
                replacementOrCurrent(nextFocused, focused),
                replacementOrCurrent(nextInViewport, inViewport),
                replacementOrCurrent(nextClickable, clickable),
                replacementOrCurrent(nextCovered, covered),
                replacementOrCurrent(nextStability, stability));
    }

    private static <T> T replacementOrCurrent(T replacement, T current) {
        return replacement == null ? current : replacement;
    }

    private static Map<String, String> immutableAttributes(Map<String, String> values) {
        Objects.requireNonNull(values, "attributes");
        Map<String, String> result = new LinkedHashMap<>();
        values.forEach(
                (key, value) ->
                        result.put(
                                requireValue(key, "attribute name"),
                                requireValue(value, "attribute value")));
        return Map.copyOf(result);
    }

    private static <T> Optional<T> requireOptional(Optional<T> value, String name) {
        return Objects.requireNonNull(value, name);
    }

    private static Optional<String> normalizeOptional(Optional<String> value, String name) {
        return requireOptional(value, name).map(item -> requireValue(item, name));
    }

    private static String requireValue(String value, String name) {
        Objects.requireNonNull(value, name);
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(name + " cannot be blank");
        }
        return normalized;
    }

    private static void requirePositive(Duration value, String name) {
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }
}
