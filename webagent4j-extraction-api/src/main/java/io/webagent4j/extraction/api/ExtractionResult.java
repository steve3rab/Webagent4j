package io.webagent4j.extraction.api;

import java.util.Objects;
import java.util.Optional;

/**
 * Immutable outcome of a successful extraction: the converted, validated value; the pre-conversion
 * raw string when this result came from a single scalar read (empty for a list or table result,
 * which aggregate many raw reads rather than carrying one); and where the value came from.
 *
 * @param value the converted, validated value
 * @param rawValue the pre-conversion raw string, for a single {@code TEXT}/{@code ATTRIBUTE}/
 *     {@code VALUE} result only
 * @param provenance where this value came from
 * @param <T> the extraction result's type
 */
public record ExtractionResult<T>(
        T value, Optional<String> rawValue, ExtractionProvenance provenance) {

    /** Validates required fields. */
    public ExtractionResult {
        Objects.requireNonNull(rawValue, "rawValue");
        Objects.requireNonNull(provenance, "provenance");
    }
}
