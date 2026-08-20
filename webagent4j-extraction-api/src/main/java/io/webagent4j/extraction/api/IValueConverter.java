package io.webagent4j.extraction.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Objects;

/**
 * Deterministically converts one raw extracted string to a target type.
 *
 * <p>Conversion is always explicit and deterministic - never a probabilistic or "best effort"
 * guess. An input that does not cleanly convert raises {@link ExtractionConversionException} rather
 * than silently substituting a default or a partial parse.
 *
 * @param <T> the converted value's type
 */
@FunctionalInterface
public interface IValueConverter<T> {

    /**
     * Converts {@code raw} to {@code T}.
     *
     * @throws ExtractionConversionException if {@code raw} cannot be deterministically converted
     */
    T convert(String raw);

    /** Returns the raw string unchanged. */
    static IValueConverter<String> identity() {
        return raw -> raw;
    }

    /** Parses a base-10 integer, rejecting leading/trailing whitespace and non-digit content. */
    static IValueConverter<Integer> toInteger() {
        return raw -> attempt(raw, Integer.class, Integer::parseInt);
    }

    /** Parses a base-10 long, rejecting leading/trailing whitespace and non-digit content. */
    static IValueConverter<Long> toLong() {
        return raw -> attempt(raw, Long.class, Long::parseLong);
    }

    /** Parses an exact decimal number with no binary floating-point rounding. */
    static IValueConverter<BigDecimal> toBigDecimal() {
        return raw -> attempt(raw, BigDecimal.class, BigDecimal::new);
    }

    /**
     * Parses {@code "true"}/{@code "false"} case-insensitively, after trimming. Any other value -
     * never a heuristic like {@code "yes"} or {@code "1"} - fails conversion explicitly.
     */
    static IValueConverter<Boolean> toBoolean() {
        return raw ->
                attempt(
                        raw,
                        Boolean.class,
                        value -> {
                            String trimmed = value.trim();
                            if (trimmed.equalsIgnoreCase("true")) {
                                return Boolean.TRUE;
                            }
                            if (trimmed.equalsIgnoreCase("false")) {
                                return Boolean.FALSE;
                            }
                            throw new IllegalArgumentException(
                                    "expected exactly \"true\" or \"false\"");
                        });
    }

    /** Parses an ISO-8601 date ({@code yyyy-MM-dd}). */
    static IValueConverter<LocalDate> toLocalDate() {
        return toLocalDate(DateTimeFormatter.ISO_LOCAL_DATE);
    }

    /** Parses a date using an explicit, caller-supplied format - never an inferred one. */
    static IValueConverter<LocalDate> toLocalDate(DateTimeFormatter formatter) {
        Objects.requireNonNull(formatter, "formatter");
        return raw ->
                attempt(
                        raw,
                        LocalDate.class,
                        value -> {
                            try {
                                return LocalDate.parse(value, formatter);
                            } catch (DateTimeParseException parseFailure) {
                                throw new IllegalArgumentException(parseFailure.getMessage());
                            }
                        });
    }

    private static <T> T attempt(
            String raw, Class<T> targetType, java.util.function.Function<String, T> parse) {
        try {
            return parse.apply(raw);
        } catch (RuntimeException failure) {
            throw new ExtractionConversionException(raw, targetType, failure);
        }
    }
}
