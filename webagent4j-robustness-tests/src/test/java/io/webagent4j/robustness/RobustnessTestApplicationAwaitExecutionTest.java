package io.webagent4j.robustness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Pure Java, no-browser proof of {@link RobustnessTestApplication#awaitExecution}'s six required
 * outcomes: an already-observed state succeeds immediately; a state published with a genuine (never
 * a fixed sleep) slight delay from another thread still succeeds; a wrong target or a duplicate
 * execution count fails immediately rather than waiting out the deadline; no event at all times out
 * deterministically; and a caller interruption - already set before the call, or delivered while
 * the call is already in progress - fails immediately with the interrupt flag left set.
 *
 * <p>Every scenario drives {@link RobustnessTestApplication}'s real {@code /track} HTTP endpoint
 * (via {@link #track}) rather than reaching into its private state: this is the same mechanism a
 * fixture's asynchronous {@code fetch('/track?...')} actually uses, so a test proving {@code
 * awaitExecution} observes it correctly is exercising the real publication path, not a stand-in for
 * it. Coordination between threads uses only {@link CountDownLatch} and {@link Thread#join} - never
 * a sleep - to stay deterministic and independent of machine speed; the one genuine bounded wait
 * (the timeout scenario) is the behavior under test, not a synchronization mechanism.
 */
class RobustnessTestApplicationAwaitExecutionTest {

    private static final Duration GENEROUS_TIMEOUT = Duration.ofSeconds(5);
    // An immediate/fail-fast outcome must return well within this bound; used only to prove the
    // call did not wait out GENEROUS_TIMEOUT, never as the pass/fail condition itself.
    private static final Duration IMMEDIATE_BOUND = Duration.ofMillis(500);
    private static final Duration SAFETY_NET_JOIN_TIMEOUT = Duration.ofSeconds(10);

    private RobustnessTestApplication application;

    @BeforeEach
    void start() throws IOException {
        application = RobustnessTestApplication.start();
    }

    @AfterEach
    void stop() {
        application.close();
    }

    @Test
    void alreadyObservedStateSucceedsImmediately() throws Exception {
        track("frame-already-there");

        long startNanos = System.nanoTime();
        application.awaitExecution("frame-already-there", 1, GENEROUS_TIMEOUT);
        Duration elapsed = Duration.ofNanos(System.nanoTime() - startNanos);

        assertThat(elapsed).isLessThan(IMMEDIATE_BOUND);
    }

    @Test
    void stateObservedWithASlightDelayFromAnotherThreadStillSucceeds() throws Exception {
        CountDownLatch awaiterStarted = new CountDownLatch(1);
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        Thread awaiter =
                new Thread(
                        () -> {
                            awaiterStarted.countDown();
                            try {
                                application.awaitExecution(
                                        "frame-delayed-publish", 1, GENEROUS_TIMEOUT);
                            } catch (Throwable failure) {
                                thrown.set(failure);
                            }
                        });

        awaiter.start();
        assertThat(awaiterStarted.await(SAFETY_NET_JOIN_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS))
                .as("awaiter thread started")
                .isTrue();
        // A real HTTP round-trip publishes the state after the awaiter has already begun polling
        // - the same "slight delay from another thread" a browser's async fetch produces, not a
        // sleep standing in for it.
        track("frame-delayed-publish");
        awaiter.join(SAFETY_NET_JOIN_TIMEOUT.toMillis());

        assertThat(awaiter.isAlive()).as("awaiter thread terminated").isFalse();
        assertThat(thrown.get()).isNull();
    }

    @Test
    void wrongTargetObservedFailsImmediately() throws Exception {
        track("wrong-target");

        long startNanos = System.nanoTime();
        assertThatThrownBy(() -> application.awaitExecution("expected-target", 1, GENEROUS_TIMEOUT))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("wrong target")
                .hasMessageContaining("expected-target")
                .hasMessageContaining("wrong-target");
        Duration elapsed = Duration.ofNanos(System.nanoTime() - startNanos);

        assertThat(elapsed).isLessThan(IMMEDIATE_BOUND);
    }

    @Test
    void executionCountAboveExpectedFailsImmediately() throws Exception {
        track("frame-dup");
        track("frame-dup");

        long startNanos = System.nanoTime();
        assertThatThrownBy(() -> application.awaitExecution("frame-dup", 1, GENEROUS_TIMEOUT))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("duplicate execution");
        Duration elapsed = Duration.ofNanos(System.nanoTime() - startNanos);

        assertThat(elapsed).isLessThan(IMMEDIATE_BOUND);
    }

    @Test
    void noEventEverArrivingTimesOutDeterministically() {
        Duration timeout = Duration.ofMillis(150);

        long startNanos = System.nanoTime();
        assertThatThrownBy(() -> application.awaitExecution("never-arrives", 1, timeout))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("timed out");
        Duration elapsed = Duration.ofNanos(System.nanoTime() - startNanos);

        assertThat(elapsed).isGreaterThanOrEqualTo(timeout);
        // A generous safety-net upper bound only, catching a genuinely broken implementation that
        // spins past its own deadline - never the proof of correctness itself.
        assertThat(elapsed).isLessThan(Duration.ofSeconds(5));
    }

    @Test
    void interruptedBeforeTheCallFailsImmediatelyAndPreservesTheInterruptFlag() throws Exception {
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        AtomicReference<Boolean> interruptFlagPreserved = new AtomicReference<>();
        Thread awaiter =
                new Thread(
                        () -> {
                            Thread.currentThread().interrupt();
                            try {
                                application.awaitExecution("never-arrives", 1, GENEROUS_TIMEOUT);
                            } catch (Throwable failure) {
                                thrown.set(failure);
                            } finally {
                                interruptFlagPreserved.set(Thread.currentThread().isInterrupted());
                            }
                        });

        awaiter.start();
        awaiter.join(SAFETY_NET_JOIN_TIMEOUT.toMillis());

        assertThat(awaiter.isAlive()).as("awaiter thread terminated").isFalse();
        assertThat(thrown.get())
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("interrupt");
        assertThat(interruptFlagPreserved.get()).isTrue();
    }

    @Test
    void interruptedWhileTheCallIsAlreadyInProgressFailsImmediatelyAndPreservesTheInterruptFlag()
            throws Exception {
        CountDownLatch awaiterStarted = new CountDownLatch(1);
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        AtomicReference<Boolean> interruptFlagPreserved = new AtomicReference<>();
        Thread awaiter =
                new Thread(
                        () -> {
                            awaiterStarted.countDown();
                            try {
                                application.awaitExecution("never-arrives", 1, GENEROUS_TIMEOUT);
                            } catch (Throwable failure) {
                                thrown.set(failure);
                            } finally {
                                interruptFlagPreserved.set(Thread.currentThread().isInterrupted());
                            }
                        });

        awaiter.start();
        assertThat(awaiterStarted.await(SAFETY_NET_JOIN_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS))
                .as("awaiter thread started")
                .isTrue();
        awaiter.interrupt();
        awaiter.join(SAFETY_NET_JOIN_TIMEOUT.toMillis());

        assertThat(awaiter.isAlive()).as("awaiter thread terminated").isFalse();
        assertThat(thrown.get())
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("interrupt");
        assertThat(interruptFlagPreserved.get()).isTrue();
    }

    /** Performs a real, blocking HTTP round-trip to the fixture's own tracking endpoint. */
    private void track(String target) throws IOException, InterruptedException {
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request =
                HttpRequest.newBuilder(URI.create(application.trackUrl(target))).build();
        client.send(request, HttpResponse.BodyHandlers.discarding());
    }
}
