package io.webagent4j.locator.api;

import java.util.Objects;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Immutable text matching criterion.
 *
 * @param type comparison mode
 * @param value non-blank requested text or regular expression
 */
public record TextMatch(TextMatchType type, String value) {

    /** Validates and normalizes the criterion. */
    public TextMatch {
        Objects.requireNonNull(type, "type");
        value = requireValue(value);
        if (type == TextMatchType.REGEX) {
            try {
                Pattern.compile(value);
            } catch (PatternSyntaxException exception) {
                throw new IllegalArgumentException(
                        "value must be a valid regular expression", exception);
            }
        }
    }

    /** Creates a case-sensitive exact criterion. */
    public static TextMatch exact(String value) {
        return new TextMatch(TextMatchType.EXACT, value);
    }

    /** Creates a case-insensitive exact criterion. */
    public static TextMatch exactIgnoringCase(String value) {
        return new TextMatch(TextMatchType.CASE_INSENSITIVE_EXACT, value);
    }

    /** Creates a case-insensitive containment criterion. */
    public static TextMatch containing(String value) {
        return new TextMatch(TextMatchType.CONTAINS, value);
    }

    /** Creates a conservative fuzzy criterion. */
    public static TextMatch fuzzy(String value) {
        return new TextMatch(TextMatchType.FUZZY, value);
    }

    private static String requireValue(String value) {
        Objects.requireNonNull(value, "value");
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("value cannot be blank");
        }
        return normalized;
    }
}
