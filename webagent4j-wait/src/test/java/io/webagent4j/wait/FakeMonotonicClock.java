package io.webagent4j.wait;

/** Deterministic clock for tests: advances only when explicitly told to. */
final class FakeMonotonicClock implements IMonotonicClock {

    private long nanos;

    @Override
    public long nanoTime() {
        return nanos;
    }

    void advance(java.time.Duration duration) {
        nanos += duration.toNanos();
    }

    /** Sets the clock to an arbitrary raw nanosecond value, for boundary/overflow tests. */
    void set(long nanoTime) {
        nanos = nanoTime;
    }
}
