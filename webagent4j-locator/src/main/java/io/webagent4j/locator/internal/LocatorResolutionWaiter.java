package io.webagent4j.locator.internal;

import io.webagent4j.common.LocatorException;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.locks.LockSupport;

/** Interruptible monotonic polling primitive used by dynamic DOM and stability resolution. */
public final class LocatorResolutionWaiter {

    /** Parks the current thread for at most the smaller supplied duration. */
    public void awaitNextPoll(Duration pollingInterval, Duration remaining) {
        Objects.requireNonNull(pollingInterval, "pollingInterval");
        Objects.requireNonNull(remaining, "remaining");
        long nanos = Math.min(pollingInterval.toNanos(), Math.max(1L, remaining.toNanos()));
        LockSupport.parkNanos(nanos);
        if (Thread.interrupted()) {
            Thread.currentThread().interrupt();
            throw new LocatorException("Locator wait was interrupted");
        }
    }
}
