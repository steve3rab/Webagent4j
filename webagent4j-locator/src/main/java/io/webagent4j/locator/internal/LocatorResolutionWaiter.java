package io.webagent4j.locator.internal;

import io.webagent4j.common.LocatorException;
import io.webagent4j.wait.IWaitSleeper;
import io.webagent4j.wait.WaitInterruptedException;
import java.time.Duration;
import java.util.Objects;

/**
 * Interruptible monotonic polling primitive used by dynamic DOM and stability resolution.
 *
 * <p>Delegates the actual parking to the shared {@link IWaitSleeper} from {@code webagent4j-wait}
 * instead of calling {@link java.util.concurrent.locks.LockSupport} directly, so the locator
 * engine's sleep behavior - and its interruption handling - is the same primitive every other
 * domain (verification, action) uses, rather than a fourth, independent implementation of the same
 * few lines.
 */
public final class LocatorResolutionWaiter {

    private final IWaitSleeper sleeper;

    /** Creates a waiter backed by the production, thread-parking sleeper. */
    public LocatorResolutionWaiter() {
        this(IWaitSleeper.parking());
    }

    /** Creates a waiter backed by an explicit sleeper, for deterministic fake-time tests. */
    public LocatorResolutionWaiter(IWaitSleeper sleeper) {
        this.sleeper = Objects.requireNonNull(sleeper, "sleeper");
    }

    /** Parks the current thread for at most the smaller supplied duration. */
    public void awaitNextPoll(Duration pollingInterval, Duration remaining) {
        Objects.requireNonNull(pollingInterval, "pollingInterval");
        Objects.requireNonNull(remaining, "remaining");
        Duration bounded =
                pollingInterval.compareTo(remaining) <= 0
                        ? pollingInterval
                        : (remaining.isNegative() ? Duration.ofNanos(1) : remaining);
        try {
            sleeper.sleep(bounded);
        } catch (WaitInterruptedException interrupted) {
            throw new LocatorException("Locator wait was interrupted");
        }
    }
}
