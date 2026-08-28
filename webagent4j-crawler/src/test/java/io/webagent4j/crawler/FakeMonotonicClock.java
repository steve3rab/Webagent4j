package io.webagent4j.crawler;

import io.webagent4j.wait.IMonotonicClock;
import java.time.Duration;

/**
 * Deterministic clock for tests: advances only when explicitly told to, never against real
 * wall-clock time - the seam that lets {@link PinnedSocketHttpTransportDeadlineCoreTest} and {@link
 * TransportInterruptionTest} simulate a shared deadline expiring, or budget having been partially
 * consumed by an earlier phase, without any real waiting.
 */
final class FakeMonotonicClock implements IMonotonicClock {

    private long nanos;

    @Override
    public long nanoTime() {
        return nanos;
    }

    void advance(Duration duration) {
        nanos += duration.toNanos();
    }
}
