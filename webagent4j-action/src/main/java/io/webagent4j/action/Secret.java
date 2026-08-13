package io.webagent4j.action;

import java.util.Objects;
import java.util.function.Function;

/** Immutable sensitive value whose textual representation is always redacted. */
public final class Secret {

    private static final String REDACTED = "[REDACTED]";
    private final String value;

    private Secret(String value) {
        this.value = value;
    }

    /** Wraps a non-null sensitive value without logging or rendering it. */
    public static Secret of(String value) {
        return new Secret(Objects.requireNonNull(value, "value"));
    }

    /** Supplies the value only to a backend operation and returns its result. */
    public <T> T use(Function<String, T> operation) {
        return Objects.requireNonNull(operation, "operation").apply(value);
    }

    /** Always returns a redacted marker. */
    @Override
    public String toString() {
        return REDACTED;
    }
}
