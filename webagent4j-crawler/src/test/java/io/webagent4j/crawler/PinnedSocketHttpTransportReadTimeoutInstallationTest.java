package io.webagent4j.crawler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.net.SocketException;
import java.net.URI;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * READ-DL-001: a deadline-sensitive blocking read may only begin once the remaining deadline has
 * actually been installed as this socket's read timeout. If installing it fails - a genuine {@link
 * SocketException} from {@code setSoTimeout}, not merely an already-expired deadline (covered
 * separately by {@code PinnedSocketHttpTransportDeadlineCoreTest}'s HD-CORE-005) - the read it
 * would have gated must never run at all, rather than silently proceeding on an unenforced timeout.
 */
class PinnedSocketHttpTransportReadTimeoutInstallationTest {

    @Test
    void aSetSoTimeoutFailureMeansTheGatedReadNeverRuns() {
        FakeMonotonicClock clock = new FakeMonotonicClock();
        // Plenty of budget left - this is not the already-expired-deadline case; the failure is
        // specifically in applying the timeout, not in the deadline itself.
        PinnedSocketHttpTransport.Deadline deadline =
                new PinnedSocketHttpTransport.Deadline(clock, Duration.ofSeconds(30));
        AtomicInteger readInvocations = new AtomicInteger();
        FailingSetSoTimeoutSocket socket = new FailingSetSoTimeoutSocket();
        CountingInputStream in = new CountingInputStream(readInvocations);
        PinnedSocketHttpTransport.ReadState state =
                new PinnedSocketHttpTransport.ReadState(in, socket, deadline);

        assertThatThrownBy(state::refreshTimeout).isInstanceOf(IOException.class);
        assertThat(readInvocations.get()).isZero();
    }

    @Test
    void wholeResponseReadingNeverInvokesReadWhenTheSocketTimeoutCannotBeApplied() {
        FakeMonotonicClock clock = new FakeMonotonicClock();
        PinnedSocketHttpTransport.Deadline deadline =
                new PinnedSocketHttpTransport.Deadline(clock, Duration.ofSeconds(30));
        AtomicInteger readInvocations = new AtomicInteger();
        FailingSetSoTimeoutSocket socket = new FailingSetSoTimeoutSocket();
        CountingInputStream in = new CountingInputStream(readInvocations);
        URI uri = URI.create("http://pinned.example.test/");
        PinnedSocketHttpTransport.ReadState state =
                new PinnedSocketHttpTransport.ReadState(in, socket, deadline);
        HttpFetchRequest request =
                new HttpFetchRequest(uri, Duration.ofSeconds(30), java.util.Map.of(), 10_000);

        // readResponse() calls refreshTimeout() before its very first byte read (the status
        // line); a failure there must propagate as an ordinary IOException without the fake
        // stream ever having been asked for a single byte.
        assertThatThrownBy(() -> PinnedSocketHttpTransport.readResponse(state, request))
                .isInstanceOf(IOException.class);
        assertThat(readInvocations.get()).isZero();
    }

    /** A {@link Socket} whose {@code setSoTimeout} always fails, never actually connected. */
    private static final class FailingSetSoTimeoutSocket extends Socket {
        @Override
        public void setSoTimeout(int timeout) throws SocketException {
            throw new SocketException("simulated failure to apply the socket timeout");
        }
    }

    /** Counts every {@code read} call without ever returning real data. */
    private static final class CountingInputStream extends InputStream {
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
