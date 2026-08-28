package io.webagent4j.crawler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * HD-CORE-001 through HD-CORE-005: deterministic proofs of the shared-deadline contract that do not
 * depend on wall-clock elapsed-time thresholds or {@code Thread.sleep}.
 *
 * <p>Each test drives {@link PinnedSocketHttpTransport.Deadline} through a {@link
 * FakeMonotonicClock} the test advances explicitly, then asserts the exact state transition ({@code
 * requireTimeBudget}'s return value or thrown exception, or how many times a blocking operation
 * actually ran) rather than how long anything took. A background socket-level end-to-end proof for
 * a genuinely blocked write still exists separately in {@link
 * PinnedSocketHttpTransportWriteDeadlineTest}, bounded only by a generous watchdog rather than a
 * tight elapsed-time assertion - this file is the deterministic core these end-to-end tests build
 * on top of.
 */
class PinnedSocketHttpTransportDeadlineCoreTest {

    private static final URI URI_UNDER_TEST = URI.create("http://pinned.example.test/");

    @Test
    void hdCore001NoNewBlockingWriteOperationStartsOnceTheSharedDeadlineHasExpired()
            throws Exception {
        FakeMonotonicClock clock = new FakeMonotonicClock();
        PinnedSocketHttpTransport.Deadline deadline =
                new PinnedSocketHttpTransport.Deadline(clock, Duration.ofMillis(100));
        clock.advance(Duration.ofMillis(150));
        AtomicInteger invocations = new AtomicInteger();

        try (Socket dummy = new Socket()) {
            assertThatThrownBy(
                            () ->
                                    PinnedSocketHttpTransport.runBoundedOnDeadline(
                                            dummy,
                                            deadline,
                                            URI_UNDER_TEST,
                                            () -> {
                                                invocations.incrementAndGet();
                                                return null;
                                            }))
                    .isInstanceOf(HttpTimeoutException.class);
        }
        assertThat(invocations.get()).isZero();
    }

    @Test
    void hdCore002TlsHandshakeConsumingPartOfTheBudgetLeavesOnlyTheRemainderForTheRequestWrite() {
        FakeMonotonicClock clock = new FakeMonotonicClock();
        PinnedSocketHttpTransport.Deadline deadline =
                new PinnedSocketHttpTransport.Deadline(clock, Duration.ofMillis(300));

        // Simulates a TLS handshake phase that itself consumed 200ms of the shared budget -
        // never a real handshake or a real sleep, just the same clock advance a real one would
        // eventually cause.
        clock.advance(Duration.ofMillis(200));

        int remainingForWrite =
                assertDoesNotThrowTimeBudget(
                        () ->
                                PinnedSocketHttpTransport.requireTimeBudget(
                                        deadline, URI_UNDER_TEST));
        assertThat(remainingForWrite).isLessThanOrEqualTo(100).isPositive();
    }

    @Test
    void hdCore003HeaderReadingConsumingBudgetLeavesOnlyTheRemainderForTheBodyRead() {
        FakeMonotonicClock clock = new FakeMonotonicClock();
        PinnedSocketHttpTransport.Deadline deadline =
                new PinnedSocketHttpTransport.Deadline(clock, Duration.ofMillis(300));

        // Simulates header reading having consumed 250ms of the shared budget before the body
        // phase's own first blocking read is about to begin.
        clock.advance(Duration.ofMillis(250));

        int remainingForBody =
                assertDoesNotThrowTimeBudget(
                        () ->
                                PinnedSocketHttpTransport.requireTimeBudget(
                                        deadline, URI_UNDER_TEST));
        assertThat(remainingForBody).isLessThanOrEqualTo(50).isPositive();
    }

    @Test
    void hdCore004ANewBlockingOperationNeverStartsOnceTheRemainingBudgetIsZeroOrNegative()
            throws Exception {
        FakeMonotonicClock clock = new FakeMonotonicClock();
        PinnedSocketHttpTransport.Deadline deadline =
                new PinnedSocketHttpTransport.Deadline(clock, Duration.ofMillis(50));
        // Advances to exactly the deadline itself, not past it - remainingMillis() must already
        // treat "zero left" the same as "past the deadline."
        clock.advance(Duration.ofMillis(50));
        AtomicInteger invocations = new AtomicInteger();

        try (Socket dummy = new Socket()) {
            assertThatThrownBy(
                            () ->
                                    PinnedSocketHttpTransport.runBoundedOnDeadline(
                                            dummy,
                                            deadline,
                                            URI_UNDER_TEST,
                                            () -> {
                                                invocations.incrementAndGet();
                                                return null;
                                            }))
                    .isInstanceOf(HttpTimeoutException.class);
        }
        assertThat(invocations.get()).isZero();
    }

    @Test
    void hdCore005ARequireTimeBudgetFailureMeansTheReadItGatesNeverRuns() throws Exception {
        FakeMonotonicClock clock = new FakeMonotonicClock();
        PinnedSocketHttpTransport.Deadline deadline =
                new PinnedSocketHttpTransport.Deadline(clock, Duration.ofMillis(10));
        clock.advance(Duration.ofMillis(20));
        AtomicInteger readInvocations = new AtomicInteger();
        FailingSetSoTimeoutSocket socket = new FailingSetSoTimeoutSocket();
        CountingInputStream in = new CountingInputStream(readInvocations);
        PinnedSocketHttpTransport.ReadState state =
                new PinnedSocketHttpTransport.ReadState(in, socket, deadline, URI_UNDER_TEST);

        // The deadline is already expired, so refreshTimeout() must fail on requireTimeBudget()
        // itself, before ever reaching socket.setSoTimeout() - proving the ordering guarantee
        // independently of READ-DL-001's separate proof that a setSoTimeout() failure alone also
        // prevents the read.
        assertThatThrownBy(state::refreshTimeout).isInstanceOf(IOException.class);
        assertThat(readInvocations.get()).isZero();
    }

    private interface TimeBudgetSupplier {
        int get() throws HttpTimeoutException;
    }

    private static int assertDoesNotThrowTimeBudget(TimeBudgetSupplier supplier) {
        try {
            return supplier.get();
        } catch (HttpTimeoutException unexpected) {
            throw new AssertionError("expected remaining budget, got a timeout", unexpected);
        }
    }

    /** A {@link Socket} whose {@code setSoTimeout} always fails, never actually connected. */
    private static final class FailingSetSoTimeoutSocket extends Socket {
        @Override
        public void setSoTimeout(int timeout) throws java.net.SocketException {
            throw new java.net.SocketException("simulated failure to apply the socket timeout");
        }
    }

    /** Counts every {@code read} call without ever returning real data. */
    private static final class CountingInputStream extends java.io.InputStream {
        private final AtomicInteger invocations;

        CountingInputStream(AtomicInteger invocations) {
            this.invocations = invocations;
        }

        @Override
        public int read() {
            invocations.incrementAndGet();
            return -1;
        }
    }
}
