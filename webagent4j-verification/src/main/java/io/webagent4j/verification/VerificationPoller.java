package io.webagent4j.verification;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/** Central polling loop used by action preconditions and postconditions. */
public final class VerificationPoller {

    private final Clock clock;

    /** Creates a poller using the system clock. */
    public VerificationPoller() {
        this(Clock.systemUTC());
    }

    /** Creates a poller with an injectable clock for deterministic tests. */
    public VerificationPoller(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Polls one side-effect-free condition until success or the positive timeout expires. */
    public VerificationResult await(
            IVerification verification,
            IVerificationContext context,
            Duration timeout,
            Duration interval) {
        Objects.requireNonNull(verification, "verification");
        Objects.requireNonNull(context, "context");
        requirePositive(timeout, "timeout");
        requirePositive(interval, "interval");
        Instant started = clock.instant();
        VerificationResult latest;
        do {
            if (Thread.currentThread().isInterrupted()) {
                Thread.currentThread().interrupt();
                throw new VerificationInterruptedException("Verification polling was interrupted");
            }
            latest = verification.verify(context);
            Duration elapsed = Duration.between(started, clock.instant());
            if (latest.success()) {
                return latest.withTiming(elapsed, false);
            }
            if (elapsed.compareTo(timeout) >= 0) {
                return latest.withTiming(elapsed, true);
            }
            pause(shorter(interval, timeout.minus(elapsed)));
        } while (true);
    }

    private static void pause(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new VerificationInterruptedException(
                    "Verification polling was interrupted", exception);
        }
    }

    private static Duration shorter(Duration first, Duration second) {
        return first.compareTo(second) <= 0 ? first : second;
    }

    private static void requirePositive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }
}
