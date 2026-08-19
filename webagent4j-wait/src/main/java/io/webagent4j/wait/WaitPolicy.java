package io.webagent4j.wait;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable polling cadence: how often to probe and, optionally, how long a satisfied result must
 * remain continuously satisfied before it is accepted.
 *
 * <p>The timeout itself is deliberately not part of this policy - it belongs to the {@link
 * WaitBudget} passed to {@link WaitEngine#await(WaitBudget, WaitPolicy, IWaitProbe)}, so a policy
 * can be reused across calls that share one deadline without becoming a second, competing source of
 * truth for "how long to wait".
 */
public record WaitPolicy(Duration pollingInterval, Optional<Duration> stableFor) {

    /** Validates that both durations, when present, are positive. */
    public WaitPolicy {
        Objects.requireNonNull(pollingInterval, "pollingInterval");
        requirePositive(pollingInterval, "pollingInterval");
        Objects.requireNonNull(stableFor, "stableFor");
        stableFor.ifPresent(duration -> requirePositive(duration, "stableFor"));
    }

    /** Returns a policy that polls at {@code interval} with no stability requirement. */
    public static WaitPolicy pollingEvery(Duration interval) {
        return new WaitPolicy(interval, Optional.empty());
    }

    /** Returns a copy of this policy requiring continuous stability for {@code duration}. */
    public WaitPolicy withStableFor(Duration duration) {
        return new WaitPolicy(pollingInterval, Optional.of(duration));
    }

    private static void requirePositive(Duration value, String name) {
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }
}
