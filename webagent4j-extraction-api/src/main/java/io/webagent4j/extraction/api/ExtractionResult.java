package io.webagent4j.extraction.api;

import java.util.Objects;
import java.util.Optional;

/**
 * Immutable outcome of a successful extraction: the converted, validated value; the pre-conversion
 * raw string when this result came from a single scalar read (empty for a list or table result,
 * which aggregate many raw reads rather than carrying one); and where the value came from.
 *
 * <p>A successful result can never carry a {@code null} {@link #value()} - see {@link
 * ExtractionRequest#convertAndValidate(String)}, which turns a converter returning {@code null}
 * into an explicit {@link ExtractionConversionException} rather than letting a {@code null} reach
 * this type.
 *
 * @param value the converted, validated value, never {@code null}
 * @param rawValue the pre-conversion raw string, for a single {@code TEXT}/{@code ATTRIBUTE}/
 *     {@code VALUE} result only
 * @param provenance where this value came from
 * @param <T> the extraction result's type
 */
public record ExtractionResult<T>(
        T value, Optional<String> rawValue, ExtractionProvenance provenance) {

    /** Validates required fields. */
    public ExtractionResult {
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(rawValue, "rawValue");
        Objects.requireNonNull(provenance, "provenance");
    }
}
