package io.webagent4j.wait;

import java.util.Objects;
import java.util.Optional;

/**
 * One side-effect-free reading taken by an {@link IWaitProbe}.
 *
 * <p>A sample is either {@link Status#PENDING} (keep polling) or {@link Status#SATISFIED} (the
 * condition currently holds). A satisfied sample may carry a {@code stabilityKey}: an opaque,
 * caller-defined identity of "the thing that is satisfied", used only when the {@link WaitPolicy}
 * requests a stability window. Two satisfied samples are considered "the same thing remaining
 * satisfied" when their stability keys are {@link Object#equals(Object)}; a different key resets
 * the stability window, even though both samples are individually satisfied. A stability key should
 * be a value with a real, stable identity - never free text such as an accessible name, visible
 * label, or diagnostic description, which can coincidentally repeat for two different underlying
 * things.
 *
 * <p>A pending sample may optionally carry an informational last-known value (via {@link
 * #pending(Object)}) - never treated as "satisfied", but preserved in {@link WaitResult#value()} if
 * the wait ultimately times out, so a caller can still see, for example, the best candidates found
 * so far even though none was accepted as the final answer.
 */
public record WaitSample<T>(Status status, Optional<T> value, Optional<Object> stabilityKey) {

    /** Whether a sample currently satisfies the awaited condition. */
    public enum Status {
        PENDING,
        SATISFIED
    }

    /** Validates internal consistency. */
    public WaitSample {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(stabilityKey, "stabilityKey");
        if (status == Status.PENDING && stabilityKey.isPresent()) {
            throw new IllegalArgumentException("a pending sample must not carry a stability key");
        }
    }

    /**
     * Returns a satisfied sample with no stability identity; any {@code stableFor} policy fails.
     */
    public static <T> WaitSample<T> satisfied(T value) {
        return new WaitSample<>(Status.SATISFIED, Optional.of(value), Optional.empty());
    }

    /** Returns a satisfied sample identified by {@code stabilityKey} for stability tracking. */
    public static <T> WaitSample<T> satisfied(T value, Object stabilityKey) {
        return new WaitSample<>(Status.SATISFIED, Optional.of(value), Optional.of(stabilityKey));
    }

    /** Returns a pending sample carrying no informational value: the condition does not hold. */
    public static <T> WaitSample<T> pending() {
        return new WaitSample<>(Status.PENDING, Optional.empty(), Optional.empty());
    }

    /**
     * Returns a pending sample carrying an informational last-known value - still retried exactly
     * like {@link #pending()}, but preserved in {@link WaitResult#value()} on a timeout instead of
     * being discarded.
     */
    public static <T> WaitSample<T> pending(T lastValue) {
        return new WaitSample<>(
                Status.PENDING,
                Optional.of(Objects.requireNonNull(lastValue, "lastValue")),
                Optional.empty());
    }
}
