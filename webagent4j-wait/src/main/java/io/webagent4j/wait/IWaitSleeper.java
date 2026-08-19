package io.webagent4j.wait;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.locks.LockSupport;

/**
 * Parks the current thread between polls.
 *
 * <p>A zero or negative duration is a no-op: the engine never needs to sleep past an already
 * expired budget, and {@link WaitEngine} may compute a sleep duration of exactly zero when the
 * remaining budget has been fully consumed by the last probe.
 */
@FunctionalInterface
public interface IWaitSleeper {

    /**
     * Parks the current thread for approximately {@code duration}.
     *
     * @throws WaitInterruptedException if the thread was already interrupted, or becomes
     *     interrupted while parked; the thread's interrupt status is preserved either way
     */
    void sleep(Duration duration);

    /** Returns the production sleeper, backed by {@link LockSupport#parkNanos(long)}. */
    static IWaitSleeper parking() {
        return SystemWaitSleeper.INSTANCE;
    }

    /** {@link LockSupport}-backed sleeper used outside of tests. */
    final class SystemWaitSleeper implements IWaitSleeper {

        private static final IWaitSleeper INSTANCE = new SystemWaitSleeper();

        private SystemWaitSleeper() {
            // single shared stateless instance
        }

        @Override
        public void sleep(Duration duration) {
            Objects.requireNonNull(duration, "duration");
            if (Thread.currentThread().isInterrupted()) {
                throw new WaitInterruptedException("Wait was interrupted before sleeping");
            }
            if (duration.isZero() || duration.isNegative()) {
                return;
            }
            LockSupport.parkNanos(duration.toNanos());
            if (Thread.interrupted()) {
                Thread.currentThread().interrupt();
                throw new WaitInterruptedException("Wait was interrupted while sleeping");
            }
        }
    }
}
