package io.webagent4j.extraction.api;

import java.util.Objects;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * Validates one already-converted extraction value. Validation always runs after conversion has
 * already succeeded - see {@link ExtractionRequest}'s raw-to-convert-to-validate pipeline.
 *
 * @param <T> the validated value's type
 */
@FunctionalInterface
public interface IExtractionValidator<T> {

    /**
     * Validates {@code value}.
     *
     * @throws ExtractionValidationException if {@code value} fails this rule
     */
    void validate(T value);

    /** Rejects a blank (empty or whitespace-only) string. */
    static IExtractionValidator<String> nonBlank() {
        return value -> {
            if (value == null || value.isBlank()) {
                throw new ExtractionValidationException(value, "must not be blank");
            }
        };
    }

    /** Rejects a value strictly outside the inclusive {@code [min, max]} range. */
    static <T extends Comparable<T>> IExtractionValidator<T> range(T min, T max) {
        Objects.requireNonNull(min, "min");
        Objects.requireNonNull(max, "max");
        if (min.compareTo(max) > 0) {
            throw new IllegalArgumentException("min must not be greater than max");
        }
        return value -> {
            if (value.compareTo(min) < 0 || value.compareTo(max) > 0) {
                throw new ExtractionValidationException(
                        value, "must be between " + min + " and " + max + " inclusive");
            }
        };
    }

    /** Rejects a string that does not fully match {@code pattern}. */
    static IExtractionValidator<String> matches(Pattern pattern) {
        Objects.requireNonNull(pattern, "pattern");
        return value -> {
            if (value == null || !pattern.matcher(value).matches()) {
                throw new ExtractionValidationException(
                        value, "must match pattern " + pattern.pattern());
            }
        };
    }

    /**
     * Rejects a value that does not satisfy {@code predicate}, described by {@code description}.
     */
    static <T> IExtractionValidator<T> predicate(Predicate<T> predicate, String description) {
        Objects.requireNonNull(predicate, "predicate");
        Objects.requireNonNull(description, "description");
        return value -> {
            if (!predicate.test(value)) {
                throw new ExtractionValidationException(value, description);
            }
        };
    }
}
