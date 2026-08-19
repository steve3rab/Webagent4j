package io.webagent4j.extraction.api;

import io.webagent4j.locator.api.LocatorDefinition;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable, backend-neutral description of one extraction intent: where to find the source ({@link
 * #source()}, reusing the existing deterministic locator resolution - no second DOM resolution
 * engine), what raw datum to read from it ({@link #readType()}), and how to deterministically
 * convert and validate that raw value.
 *
 * <p>The same request describes both a single extraction (one source, one result) and a list
 * extraction (every matching source, one result per candidate, in the engine's deterministic order)
 * - which of the two applies is a property of which extraction engine method a request is passed
 * to, not of the request itself. {@link #source()}'s own {@link LocatorDefinition#timeout()}/{@link
 * LocatorDefinition#stability()} govern the wait, exactly as they already do for {@code
 * IFrame#locate}: extraction introduces no separate timeout concept.
 *
 * <p>The pipeline is always raw -&gt; convert -&gt; validate: {@link #convert(IValueConverter)}
 * changes the request's result type and therefore always discards any previously attached validator
 * (a validator typed for the old result type can never apply to the new one); attach {@link
 * #validate(IExtractionValidator)} again afterward.
 *
 * @param <T> the converted extraction result's type
 */
public record ExtractionRequest<T>(
        LocatorDefinition source,
        ExtractionReadType readType,
        Optional<String> attributeName,
        Optional<IValueConverter<T>> converter,
        Optional<IExtractionValidator<T>> validator) {

    /** Validates internal consistency between {@link #readType()} and {@link #attributeName()}. */
    public ExtractionRequest {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(readType, "readType");
        Objects.requireNonNull(attributeName, "attributeName");
        Objects.requireNonNull(converter, "converter");
        Objects.requireNonNull(validator, "validator");
        if (readType == ExtractionReadType.ATTRIBUTE && attributeName.isEmpty()) {
            throw new IllegalArgumentException("ATTRIBUTE requests require an attributeName");
        }
        if (readType != ExtractionReadType.ATTRIBUTE && attributeName.isPresent()) {
            throw new IllegalArgumentException(
                    "attributeName is only meaningful for ATTRIBUTE requests");
        }
    }

    /** Requests the source element's normalized visible text. */
    public static ExtractionRequest<String> text(LocatorDefinition source) {
        return new ExtractionRequest<>(
                source,
                ExtractionReadType.TEXT,
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
    }

    /** Requests one named HTML attribute from the source element. */
    public static ExtractionRequest<String> attribute(LocatorDefinition source, String name) {
        Objects.requireNonNull(name, "name");
        return new ExtractionRequest<>(
                source,
                ExtractionReadType.ATTRIBUTE,
                Optional.of(name),
                Optional.empty(),
                Optional.empty());
    }

    /** Requests the source element's current live form-control value. */
    public static ExtractionRequest<String> value(LocatorDefinition source) {
        return new ExtractionRequest<>(
                source,
                ExtractionReadType.VALUE,
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
    }

    /**
     * Returns a copy that converts the raw string with {@code converter} - replacing this request's
     * result type, and therefore its previous converter and validator (if any), since neither was
     * typed for the new result type.
     */
    public <R> ExtractionRequest<R> convert(IValueConverter<R> converter) {
        Objects.requireNonNull(converter, "converter");
        return new ExtractionRequest<>(
                source, readType, attributeName, Optional.of(converter), Optional.empty());
    }

    /** Returns a copy that additionally validates the converted value with {@code validator}. */
    public ExtractionRequest<T> validate(IExtractionValidator<T> validator) {
        Objects.requireNonNull(validator, "validator");
        return new ExtractionRequest<>(
                source, readType, attributeName, converter, Optional.of(validator));
    }
}
