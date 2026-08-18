package io.webagent4j.wait;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Test sleeper that never really sleeps: it advances a {@link FakeMonotonicClock} by exactly the
 * requested duration and records every call, so a test asserting a 60-second timeout runs in
 * microseconds while still exercising the engine's real interval/deadline arithmetic.
 */
final class FakeWaitSleeper implements IWaitSleeper {

    private final FakeMonotonicClock clock;
    private final List<Duration> sleeps = new ArrayList<>();
    private boolean interruptOnNextSleep;

    FakeWaitSleeper(FakeMonotonicClock clock) {
        this.clock = clock;
    }

    @Override
    public void sleep(Duration duration) {
        if (interruptOnNextSleep) {
            interruptOnNextSleep = false;
            Thread.currentThread().interrupt();
            throw new WaitInterruptedException("Wait was interrupted while sleeping");
        }
        sleeps.add(duration);
        clock.advance(duration);
    }

    List<Duration> sleeps() {
        return List.copyOf(sleeps);
    }

    void interruptOnNextSleep() {
        interruptOnNextSleep = true;
    }
}
