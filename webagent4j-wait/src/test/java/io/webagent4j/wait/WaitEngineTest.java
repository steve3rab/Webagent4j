package io.webagent4j.wait;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Proves the engine's polling, deadline, stability, and interruption contract using fake time. */
class WaitEngineTest {

    private final FakeMonotonicClock clock = new FakeMonotonicClock();
    private final FakeWaitSleeper sleeper = new FakeWaitSleeper(clock);
    private final WaitEngine engine = new WaitEngine(clock, sleeper);

    @AfterEach
    void clearInterruptStatus() {
        Thread.interrupted();
    }

    @Test
    void succeedsImmediatelyWithoutSleepingWhenAlreadySatisfied() {
        WaitResult<String> result =
                engine.await(
                        Duration.ofSeconds(60),
                        WaitPolicy.pollingEvery(Duration.ofMillis(100)),
                        () -> WaitSample.satisfied("ready"));

        assertThat(result.success()).isTrue();
        assertThat(result.attempts()).isEqualTo(1);
        assertThat(result.value()).contains("ready");
        assertThat(sleeper.sleeps()).isEmpty();
    }

    @Test
    void succeedsAfterAFixedNumberOfPendingAttempts() {
        Deque<WaitSample<String>> samples =
                sequence(
                        WaitSample.pending(),
                        WaitSample.pending(),
                        WaitSample.pending(),
                        WaitSample.satisfied("ready"));

        WaitResult<String> result =
                engine.await(
                        Duration.ofSeconds(5),
                        WaitPolicy.pollingEvery(Duration.ofMillis(50)),
                        samples::poll);

        assertThat(result.success()).isTrue();
        assertThat(result.attempts()).isEqualTo(4);
        assertThat(sleeper.sleeps())
                .containsExactly(
                        Duration.ofMillis(50), Duration.ofMillis(50), Duration.ofMillis(50));
    }

    @Test
    void timesOutWithoutSleepingPastTheDeadline() {
        WaitResult<String> result =
                engine.await(
                        Duration.ofMillis(1000),
                        WaitPolicy.pollingEvery(Duration.ofMillis(100)),
                        WaitSample::pending);

        assertThat(result.status()).isEqualTo(WaitStatus.TIMED_OUT);
        assertThat(result.elapsed()).isEqualTo(Duration.ofMillis(1000));
        assertThat(sleeper.sleeps())
                .allSatisfy(d -> assertThat(d).isLessThanOrEqualTo(Duration.ofMillis(100)));
        assertThat(sleeper.sleeps().stream().reduce(Duration.ZERO, Duration::plus))
                .isEqualTo(Duration.ofMillis(1000));
    }

    @Test
    void neverSleepsLongerThanTheRemainingBudget() {
        Deque<WaitSample<String>> samples = sequence(WaitSample.pending(), WaitSample.pending());

        engine.await(
                Duration.ofMillis(40),
                WaitPolicy.pollingEvery(Duration.ofMillis(100)),
                samples::poll);

        assertThat(sleeper.sleeps()).containsExactly(Duration.ofMillis(40));
    }

    @Test
    void stopsImmediatelyWhenAlreadyInterruptedBeforeTheFirstPoll() {
        Thread.currentThread().interrupt();

        assertThatExceptionOfType(WaitInterruptedException.class)
                .isThrownBy(
                        () ->
                                engine.await(
                                        Duration.ofSeconds(1),
                                        WaitPolicy.pollingEvery(Duration.ofMillis(10)),
                                        WaitSample::pending));
        assertThat(Thread.currentThread().isInterrupted()).isTrue();
    }

    @Test
    void stopsAndPreservesInterruptStatusWhenInterruptedDuringSleep() {
        sleeper.interruptOnNextSleep();

        assertThatExceptionOfType(WaitInterruptedException.class)
                .isThrownBy(
                        () ->
                                engine.await(
                                        Duration.ofSeconds(1),
                                        WaitPolicy.pollingEvery(Duration.ofMillis(10)),
                                        WaitSample::pending));
        assertThat(Thread.currentThread().isInterrupted()).isTrue();
    }

    @Test
    void succeedsOnceTheSameCandidateHasBeenContinuouslyStableLongEnough() {
        Deque<WaitSample<String>> samples =
                sequence(
                        WaitSample.satisfied("A", "identity-A"),
                        WaitSample.satisfied("A", "identity-A"),
                        WaitSample.satisfied("A", "identity-A"),
                        WaitSample.satisfied("A", "identity-A"));

        WaitResult<String> result =
                engine.await(
                        Duration.ofSeconds(5),
                        WaitPolicy.pollingEvery(Duration.ofMillis(100))
                                .withStableFor(Duration.ofMillis(300)),
                        samples::poll);

        assertThat(result.success()).isTrue();
        assertThat(result.attempts()).isEqualTo(4);
        assertThat(result.achievedStability()).contains(Duration.ofMillis(300));
    }

    @Test
    void resetsTheStabilityWindowWhenTheCandidateIdentityChanges() {
        Deque<WaitSample<String>> samples =
                sequence(
                        WaitSample.satisfied("A", "identity-A"),
                        WaitSample.satisfied("A", "identity-A"),
                        WaitSample.satisfied("B", "identity-B"),
                        WaitSample.satisfied("B", "identity-B"),
                        WaitSample.satisfied("B", "identity-B"),
                        WaitSample.satisfied("B", "identity-B"));

        WaitResult<String> result =
                engine.await(
                        Duration.ofSeconds(5),
                        WaitPolicy.pollingEvery(Duration.ofMillis(100))
                                .withStableFor(Duration.ofMillis(250)),
                        samples::poll);

        assertThat(result.success()).isTrue();
        assertThat(result.value()).contains("B");
        // Had A's two satisfied attempts wrongly counted toward B's window, success would have
        // landed after 3 attempts (200ms of "any satisfied" time) instead of 6.
        assertThat(result.attempts()).isEqualTo(6);
    }

    @Test
    void aPendingAttemptResetsAnInProgressStabilityWindowEvenIfSatisfiedAgainAfterward() {
        Deque<WaitSample<String>> samples =
                sequence(
                        WaitSample.satisfied("A", "identity-A"),
                        WaitSample.satisfied("A", "identity-A"),
                        WaitSample.pending(),
                        WaitSample.satisfied("A", "identity-A"),
                        WaitSample.satisfied("A", "identity-A"),
                        WaitSample.satisfied("A", "identity-A"));

        // A naive accumulator that summed every satisfied attempt's polling interval (ignoring the
        // flicker) would already have 200ms + 200ms = 400ms >= 200ms by the fourth or fifth
        // attempt. Requiring the window to expire proves the flicker genuinely reset the timer.
        WaitResult<String> result =
                engine.await(
                        Duration.ofMillis(450),
                        WaitPolicy.pollingEvery(Duration.ofMillis(100))
                                .withStableFor(Duration.ofMillis(200)),
                        samples::poll);

        assertThat(result.status()).isEqualTo(WaitStatus.TIMED_OUT);
    }

    @Test
    void aStabilityPolicyWithoutAStabilityKeyFromTheProbeFailsExplicitly() {
        assertThatIllegalStateException()
                .isThrownBy(
                        () ->
                                engine.await(
                                        Duration.ofSeconds(1),
                                        WaitPolicy.pollingEvery(Duration.ofMillis(10))
                                                .withStableFor(Duration.ofMillis(50)),
                                        () -> WaitSample.satisfied("no-key")));
    }

    @SafeVarargs
    private static <T> Deque<WaitSample<T>> sequence(WaitSample<T>... samples) {
        return new ArrayDeque<>(List.of(samples));
    }
}
