package io.webagent4j.observation;

import java.util.Objects;
import java.util.Optional;

/**
 * Immutable value metadata applying redaction before a semantic observation is constructed.
 *
 * <p>A redacted value never retains the original secret. Its string representation is therefore
 * safe for diagnostics and serialization.
 *
 * @param disposition collection or redaction outcome
 * @param value retained non-sensitive value only
 * @param valuePresent whether the source control contained a value
 */
public record ObservedValue(
        ValueDisposition disposition, Optional<String> value, boolean valuePresent) {

    /** Enforces the invariant that only plain values may carry text. */
    public ObservedValue {
        Objects.requireNonNull(disposition, "disposition");
        value = Objects.requireNonNull(value, "value");
        if (disposition != ValueDisposition.PLAIN && value.isPresent()) {
            throw new IllegalArgumentException("only plain values may retain text");
        }
        if (disposition == ValueDisposition.PLAIN && value.isEmpty()) {
            throw new IllegalArgumentException("a plain value must contain text");
        }
    }

    /** Returns empty-value metadata. */
    public static ObservedValue empty() {
        return new ObservedValue(ValueDisposition.EMPTY, Optional.empty(), false);
    }

    /** Returns metadata for a deliberately omitted value. */
    public static ObservedValue omitted(boolean present) {
        return new ObservedValue(ValueDisposition.OMITTED, Optional.empty(), present);
    }

    /** Returns a retained non-sensitive value. */
    public static ObservedValue plain(String value) {
        String result = Objects.requireNonNull(value, "value");
        if (result.isEmpty()) {
            return empty();
        }
        return new ObservedValue(ValueDisposition.PLAIN, Optional.of(result), true);
    }

    /** Returns irreversible redaction metadata without retaining source text. */
    public static ObservedValue redacted(boolean present) {
        return new ObservedValue(ValueDisposition.REDACTED, Optional.empty(), present);
    }

    /** Returns whether a secret policy removed the value. */
    public boolean redacted() {
        return disposition == ValueDisposition.REDACTED;
    }
}
