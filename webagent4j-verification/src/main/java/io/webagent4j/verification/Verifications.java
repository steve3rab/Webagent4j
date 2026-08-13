package io.webagent4j.verification;

import io.webagent4j.dom.ElementState;
import io.webagent4j.dom.IElement;
import io.webagent4j.locator.api.IElementReference;
import io.webagent4j.locator.api.ILocator;
import io.webagent4j.observation.ObservationDiff;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/** Readable factories for built-in deterministic verifications. */
public final class Verifications {

    private Verifications() {}

    /** Verifies that the current URL contains a fragment. */
    public static IVerification urlContains(String fragment) {
        return new UrlContainsVerification(fragment);
    }

    /** Verifies that the current URL equals an absolute or relative expected value. */
    public static IVerification urlEquals(String expected) {
        return page(
                VerificationType.URL_EQUALS,
                "URL equals expected value",
                expected,
                IVerificationContext::url,
                expected::equals);
    }

    /** Verifies the current URL against a regular expression. */
    public static IVerification urlMatches(Pattern pattern) {
        Objects.requireNonNull(pattern, "pattern");
        return page(
                VerificationType.URL_MATCHES,
                "URL matches pattern",
                pattern.pattern(),
                IVerificationContext::url,
                value -> pattern.matcher(value).matches());
    }

    /** Verifies exact page title equality. */
    public static IVerification titleEquals(String expected) {
        return page(
                VerificationType.TITLE_EQUALS,
                "Title equals expected value",
                expected,
                IVerificationContext::title,
                expected::equals);
    }

    /** Verifies that the page title contains a fragment. */
    public static IVerification titleContains(String expected) {
        return page(
                VerificationType.TITLE_CONTAINS,
                "Title contains expected fragment",
                expected,
                IVerificationContext::title,
                value -> value.contains(expected));
    }

    /** Verifies that semantic visible page content contains text. */
    public static IVerification textVisible(String expected) {
        requireText(expected, "expected");
        return new ContextVerification(
                VerificationType.TEXT_VISIBLE,
                "Visible text is present",
                expected,
                context -> context.observe().toCompactText(),
                value -> value.contains(expected));
    }

    /** Verifies that a target can be resolved and remains present. */
    public static IVerification elementExists(IElementReference<IElement> target) {
        return element(
                target, VerificationType.ELEMENT_EXISTS, "Element exists", ElementState::present);
    }

    /** Verifies that a target cannot currently be resolved. */
    public static IVerification elementNotExists(IElementReference<IElement> target) {
        Objects.requireNonNull(target, "target");
        return new IVerification() {
            @Override
            public VerificationType type() {
                return VerificationType.ELEMENT_NOT_EXISTS;
            }

            @Override
            public VerificationResult verify(IVerificationContext context) {
                try {
                    boolean missing = !target.resolve().state().present();
                    return result(missing, type(), "Element does not exist", "absent", "present");
                } catch (RuntimeException missing) {
                    return result(true, type(), "Element does not exist", "absent", "absent");
                }
            }
        };
    }

    /** Verifies current element visibility. */
    public static IVerification elementVisible(IElementReference<IElement> target) {
        return element(
                target,
                VerificationType.ELEMENT_VISIBLE,
                "Element is visible",
                ElementState::visible);
    }

    /** Verifies that the element exists but is hidden. */
    public static IVerification elementHidden(IElementReference<IElement> target) {
        return element(
                target, VerificationType.ELEMENT_HIDDEN, "Element is hidden", ElementState::hidden);
    }

    /** Verifies current enabled state. */
    public static IVerification elementEnabled(IElementReference<IElement> target) {
        return element(
                target,
                VerificationType.ELEMENT_ENABLED,
                "Element is enabled",
                ElementState::enabled);
    }

    /** Verifies current disabled state. */
    public static IVerification elementDisabled(IElementReference<IElement> target) {
        return element(
                target,
                VerificationType.ELEMENT_DISABLED,
                "Element is disabled",
                ElementState::disabled);
    }

    /** Verifies current editability. */
    public static IVerification elementEditable(IElementReference<IElement> target) {
        return element(
                target,
                VerificationType.ELEMENT_EDITABLE,
                "Element is editable",
                ElementState::editable);
    }

    /** Verifies current checked state. */
    public static IVerification elementChecked(IElementReference<IElement> target) {
        return element(
                target,
                VerificationType.ELEMENT_CHECKED,
                "Element is checked",
                ElementState::checked);
    }

    /** Verifies current unchecked state. */
    public static IVerification elementUnchecked(IElementReference<IElement> target) {
        return element(
                target,
                VerificationType.ELEMENT_UNCHECKED,
                "Element is unchecked",
                state -> !state.checked());
    }

    /** Verifies current selection state. */
    public static IVerification elementSelected(IElementReference<IElement> target) {
        return element(
                target,
                VerificationType.ELEMENT_SELECTED,
                "Element is selected",
                ElementState::selected);
    }

    /** Verifies current focus ownership. */
    public static IVerification elementFocused(IElementReference<IElement> target) {
        return element(
                target,
                VerificationType.ELEMENT_FOCUSED,
                "Element is focused",
                ElementState::focused);
    }

    /** Verifies normalized element text equality. */
    public static IVerification textEquals(IElementReference<IElement> target, String expected) {
        return targetValue(
                target,
                VerificationType.TEXT_EQUALS,
                "Element text equals expected value",
                expected,
                IElement::text,
                expected::equals);
    }

    /** Verifies that normalized element text contains a fragment. */
    public static IVerification textContains(IElementReference<IElement> target, String expected) {
        return targetValue(
                target,
                VerificationType.TEXT_CONTAINS,
                "Element text contains expected fragment",
                expected,
                IElement::text,
                value -> value.contains(expected));
    }

    /** Verifies an exact attribute value. */
    public static IVerification attributeEquals(
            IElementReference<IElement> target, String name, String expected) {
        requireText(name, "name");
        return targetValue(
                target,
                VerificationType.ATTRIBUTE_EQUALS,
                "Element attribute equals expected value",
                expected,
                element -> element.attributes().getOrDefault(name, ""),
                expected::equals);
    }

    /** Verifies the current input value exposed by the DOM value attribute contract. */
    public static IVerification valueEquals(IElementReference<IElement> target, String expected) {
        return targetValue(
                target,
                VerificationType.VALUE_EQUALS,
                "Element value equals expected value",
                expected,
                element -> element.attributes().getOrDefault("value", ""),
                expected::equals);
    }

    /** Creates a value verification that the action builder binds to its target. */
    public static ITargetVerification valueEquals(String expected) {
        requireText(expected, "expected");
        return new UnboundValueVerification(expected);
    }

    /** Verifies an exact element count. */
    public static IVerification elementCount(ILocator<IElement> locator, int expected) {
        if (expected < 0) {
            throw new IllegalArgumentException("expected cannot be negative");
        }
        Objects.requireNonNull(locator, "locator");
        return new IVerification() {
            @Override
            public VerificationType type() {
                return VerificationType.ELEMENT_COUNT;
            }

            @Override
            public VerificationResult verify(IVerificationContext context) {
                int actual = locator.all().size();
                return result(
                        actual == expected,
                        type(),
                        "Element count equals expected value",
                        Integer.toString(expected),
                        Integer.toString(actual));
            }
        };
    }

    /** Verifies that a semantic element was added between two observations. */
    public static IVerification elementAdded(ObservationDiff diff) {
        return diff(
                diff,
                VerificationType.ELEMENT_ADDED,
                "Semantic element was added",
                value -> !value.elementsAdded().isEmpty(),
                "elements added");
    }

    /** Verifies that a semantic element was removed between two observations. */
    public static IVerification elementRemoved(ObservationDiff diff) {
        return diff(
                diff,
                VerificationType.ELEMENT_REMOVED,
                "Semantic element was removed",
                value -> !value.elementsRemoved().isEmpty(),
                "elements removed");
    }

    /** Verifies that a named dialog was opened in an observation diff. */
    public static IVerification dialogOpened(ObservationDiff diff, String name) {
        requireText(name, "name");
        return diff(
                diff,
                VerificationType.DIALOG_OPENED,
                "Named dialog was opened",
                value ->
                        value.dialogsOpened().stream()
                                .anyMatch(dialog -> dialog.name().equalsIgnoreCase(name)),
                name);
    }

    /** Verifies that any semantic state changed. */
    public static IVerification stateChanged(ObservationDiff diff) {
        return diff(
                diff,
                VerificationType.STATE_CHANGED,
                "Semantic state changed",
                value -> !value.empty(),
                "non-empty diff");
    }

    /** Requires every child verification to succeed in encounter order. */
    public static IVerification allOf(IVerification... verifications) {
        return composite(VerificationType.ALL, true, verifications);
    }

    /** Requires at least one child verification to succeed. */
    public static IVerification anyOf(IVerification... verifications) {
        return composite(VerificationType.ANY, false, verifications);
    }

    /** Negates one verification. */
    public static IVerification not(IVerification verification) {
        Objects.requireNonNull(verification, "verification");
        return new IVerification() {
            @Override
            public VerificationType type() {
                return VerificationType.NOT;
            }

            @Override
            public VerificationResult verify(IVerificationContext context) {
                VerificationResult child = verification.verify(context);
                return result(
                        !child.success(),
                        type(),
                        "Negated verification",
                        "false",
                        Boolean.toString(child.success()));
            }
        };
    }

    private static IVerification page(
            VerificationType type,
            String description,
            String expected,
            IContextValue value,
            Predicate<String> predicate) {
        requireText(expected, "expected");
        return new ContextVerification(type, description, expected, value, predicate);
    }

    private static IVerification element(
            IElementReference<IElement> target,
            VerificationType type,
            String description,
            Predicate<ElementState> predicate) {
        Objects.requireNonNull(target, "target");
        return new IVerification() {
            @Override
            public VerificationType type() {
                return type;
            }

            @Override
            public VerificationResult verify(IVerificationContext context) {
                boolean success = predicate.test(target.resolve().state());
                return result(success, type, description, "true", Boolean.toString(success));
            }
        };
    }

    private static IVerification targetValue(
            IElementReference<IElement> target,
            VerificationType type,
            String description,
            String expected,
            IElementValue value,
            Predicate<String> predicate) {
        Objects.requireNonNull(target, "target");
        requireText(expected, "expected");
        return new IVerification() {
            @Override
            public VerificationType type() {
                return type;
            }

            @Override
            public VerificationResult verify(IVerificationContext context) {
                String actual = value.read(target.resolve());
                return result(predicate.test(actual), type, description, expected, actual);
            }
        };
    }

    private static IVerification diff(
            ObservationDiff diff,
            VerificationType type,
            String description,
            Predicate<ObservationDiff> predicate,
            String expected) {
        Objects.requireNonNull(diff, "diff");
        return new IVerification() {
            @Override
            public VerificationType type() {
                return type;
            }

            @Override
            public VerificationResult verify(IVerificationContext context) {
                boolean success = predicate.test(diff);
                return result(success, type, description, expected, Boolean.toString(success));
            }
        };
    }

    private static IVerification composite(
            VerificationType type, boolean requireAll, IVerification... values) {
        List<IVerification> verifications = List.copyOf(Arrays.asList(values));
        if (verifications.isEmpty() || verifications.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("verifications must contain non-null values");
        }
        return new IVerification() {
            @Override
            public VerificationType type() {
                return type;
            }

            @Override
            public VerificationResult verify(IVerificationContext context) {
                List<VerificationResult> results =
                        verifications.stream().map(value -> value.verify(context)).toList();
                boolean success =
                        requireAll
                                ? results.stream().allMatch(VerificationResult::success)
                                : results.stream().anyMatch(VerificationResult::success);
                return result(
                        success,
                        type,
                        requireAll ? "All verifications succeed" : "Any verification succeeds",
                        Boolean.toString(true),
                        Boolean.toString(success));
            }
        };
    }

    private static VerificationResult result(
            boolean success,
            VerificationType type,
            String description,
            String expected,
            String actual) {
        return new VerificationResult(
                success, type, description, expected, actual, Duration.ZERO, false);
    }

    private static void requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " cannot be blank");
        }
    }

    @FunctionalInterface
    private interface IContextValue {
        String read(IVerificationContext context);
    }

    @FunctionalInterface
    private interface IElementValue {
        String read(IElement element);
    }

    private record ContextVerification(
            VerificationType type,
            String description,
            String expected,
            IContextValue value,
            Predicate<String> predicate)
            implements IVerification {
        @Override
        public VerificationResult verify(IVerificationContext context) {
            String actual = value.read(context);
            return result(predicate.test(actual), type, description, expected, actual);
        }
    }

    private record UnboundValueVerification(String expected) implements ITargetVerification {
        @Override
        public VerificationType type() {
            return VerificationType.VALUE_EQUALS;
        }

        @Override
        public VerificationResult verify(IVerificationContext context) {
            throw new IllegalStateException("Target verification must be bound by an action");
        }

        @Override
        public IVerification bind(IElementReference<IElement> target) {
            return valueEquals(target, expected);
        }
    }
}
