package io.webagent4j.crawler;

import static org.assertj.core.api.Assertions.assertThat;

import io.webagent4j.policy.network.VerifiedNetworkAddresses;
import io.webagent4j.wait.IMonotonicClock;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * INT-001 through INT-003 (TLS/write/read) and INT-CONNECT-001 (connect): interruption of the
 * calling thread is a terminal outcome, never an ordinary connectivity failure eligible for a
 * pinned-address fallback, regardless of which phase of one attempt it happens during. Each test
 * blocks a real fetch on a fixture that deliberately never completes the phase under test, waits
 * (via a {@link CountDownLatch} the fixture itself signals, never a sleep) until that phase has
 * genuinely begun, interrupts the fetching thread, and asserts the resulting exception type, the
 * interrupt flag, and that a second pinned address was never contacted - never on elapsed
 * wall-clock time.
 *
 * <p>The connect phase needs a different synchronization technique than TLS/write/read: unlike
 * those phases, a real, silently-dropped connect attempt gives no peer-observable event (nothing is
 * ever accepted) a test could synchronize on. {@link
 * #intConnect001InterruptDuringConnectNeverFallsBackToASecondPinnedAddress} instead injects a
 * deterministic, test-controlled {@link PinnedSocketHttpTransport.ISocketConnector} - the
 * package-private seam the production code exercises through the real {@code Socket::connect} -
 * that signals a latch the instant it is invoked and then blocks until interrupted, exactly as a
 * real connect attempt that never receives a response would.
 *
 * <p>INT-PRE-001 and INT-FALLBACK-001 cover a boundary distinct from all of the above: a caller
 * whose interruption is already known - either before {@code fetch()} is ever called, or observed
 * only after one pinned address's ordinary connectivity failure - must never let a new physical
 * network operation start at all, rather than starting one and then merely stopping it once already
 * in flight. {@link
 * #intPre001ACallerAlreadyInterruptedBeforeFetchNeverStartsAnyPhysicalNetworkOperation} proves the
 * first case using the same {@link PinnedSocketHttpTransport.ISocketConnector} seam, asserting the
 * connector is invoked zero times. {@link
 * #intFallback001InterruptObservedAfterAnOrdinaryFailureBlocksTheNextPinnedAddress} proves the
 * second case using {@link InterruptOnNextClockReadAfterArmed}, a deterministic {@link
 * IMonotonicClock} test double that interrupts the fetching thread on its own very next clock read
 * once armed - armed only from inside the first address's connector invocation, so the {@code
 * Future} the transport itself already waits on is what guarantees the interrupt is visible no
 * earlier than that address's failure has been fully processed, without any real timing race.
 *
 * <p>INT-004 (a configured crawler retry policy must not retry after interruption) lives in {@link
 * HttpCrawlerTest} instead, next to the rest of that class's retry-classification coverage; since
 * HttpCrawler dispatches on the interruption exception's type alone, that one test already covers
 * every phase an interruption could originate from, connect included.
 *
 * <p>Every test's real, physical fixture binds only to the IPv4 loopback ({@code 127.0.0.1}) - the
 * one loopback address guaranteed bindable without host-level configuration on every platform this
 * suite runs on. A second numbered {@code 127.0.0.x} alias binds without any setup on Linux, whose
 * kernel treats the entire {@code 127.0.0.0/8} block as local, but not on macOS, which only
 * recognizes {@code 127.0.0.1} unless a second address is explicitly aliased onto the loopback
 * interface - exactly the {@code BindException: Can't assign requested address} this suite must
 * never risk. The second, never-really-contacted pinned address is never bound to anything at all:
 * {@link #delegatingButGuardingSecondAddress} routes a connect attempt for it straight to a counter
 * instead of the network, so its {@link InetAddress} value only ever needs to be distinct from the
 * first address's, never independently bindable or routable - with no change to which address is
 * contacted first, how many times, or what a genuine connectivity failure there would look like to
 * the code under test.
 */
class TransportInterruptionTest {

    @Test
    void intConnect001InterruptDuringConnectNeverFallsBackToASecondPinnedAddress()
            throws Exception {
        InetAddress firstAddress = InetAddress.getByName("127.0.0.1");
        InetAddress secondAddress = InetAddress.getByName("127.0.0.2");
        CountDownLatch connectStarted = new CountDownLatch(1);
        AtomicInteger connectInvocations = new AtomicInteger();
        AtomicInteger secondAddressAttempts = new AtomicInteger();
        PinnedSocketHttpTransport.ISocketConnector blockingUntilInterruptedConnector =
                (socket, address, timeoutMillis) -> {
                    if (address.getAddress().equals(secondAddress)) {
                        secondAddressAttempts.incrementAndGet();
                        throw new IOException(
                                "must never be reached: the second pinned address must never be"
                                        + " contacted");
                    }
                    connectInvocations.incrementAndGet();
                    connectStarted.countDown();
                    try {
                        // Blocks exactly like a real connect attempt to a peer that never
                        // responds at all would - unblocked only by the interruption this test
                        // triggers (through Future#cancel(true) inside runBoundedOnDeadline),
                        // never by a real or simulated timeout.
                        new CountDownLatch(1).await();
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IOException("simulated connect interrupted", interrupted);
                    }
                };
        int port = 1;
        URI uri = URI.create("http://pinned.example.test:" + port + "/");
        VerifiedNetworkAddresses pinned =
                new VerifiedNetworkAddresses(
                        "pinned.example.test", port, List.of(firstAddress, secondAddress));

        FetchOutcome outcome =
                fetchOnInterruptibleThread(
                        uri,
                        pinned,
                        () -> {
                            boolean reached = connectStarted.await(10, TimeUnit.SECONDS);
                            assertThat(reached)
                                    .as("the fake connector's connect() was invoked")
                                    .isTrue();
                        },
                        Map.of(),
                        Optional.of(blockingUntilInterruptedConnector));

        assertThat(outcome.thrown())
                .isInstanceOf(PinnedSocketHttpTransport.TransportInterruptedException.class);
        PinnedSocketHttpTransport.TransportInterruptedException interrupted =
                (PinnedSocketHttpTransport.TransportInterruptedException) outcome.thrown();
        assertThat(interrupted.transmissionMayHaveStarted())
                .as("connect precedes TLS and the HTTP request write entirely")
                .isFalse();
        assertThat(outcome.interruptFlagPreserved()).isTrue();
        assertThat(connectInvocations.get())
                .as("the first pinned address's connect was attempted exactly once")
                .isEqualTo(1);
        assertThat(secondAddressAttempts.get())
                .as("the second pinned address was never contacted")
                .isEqualTo(0);
    }

    @Test
    void int001InterruptDuringTlsHandshakeNeverFallsBackToASecondPinnedAddress() throws Exception {
        InetAddress firstAddress = InetAddress.getByName("127.0.0.1");
        InetAddress secondAddress = InetAddress.getByName("127.0.0.2");
        AtomicInteger secondAddressAttempts = new AtomicInteger();
        try (SilentServer first = SilentServer.bindingTo(firstAddress)) {
            URI uri = URI.create("https://pinned.example.test:" + first.port() + "/");
            VerifiedNetworkAddresses pinned =
                    new VerifiedNetworkAddresses(
                            "pinned.example.test",
                            first.port(),
                            List.of(firstAddress, secondAddress));

            FetchOutcome outcome =
                    fetchOnInterruptibleThread(
                            uri,
                            pinned,
                            first::awaitAccepted,
                            Map.of(),
                            Optional.of(
                                    delegatingButGuardingSecondAddress(
                                            secondAddress, secondAddressAttempts)));

            assertThat(outcome.thrown())
                    .isInstanceOf(PinnedSocketHttpTransport.TransportInterruptedException.class);
            PinnedSocketHttpTransport.TransportInterruptedException interrupted =
                    (PinnedSocketHttpTransport.TransportInterruptedException) outcome.thrown();
            assertThat(interrupted.transmissionMayHaveStarted())
                    .as("TLS handshake precedes the HTTP request write entirely")
                    .isFalse();
            assertThat(outcome.interruptFlagPreserved()).isTrue();
            assertThat(secondAddressAttempts.get()).isEqualTo(0);
        }
    }

    @Test
    void int002InterruptDuringABlockedWriteNeverFallsBackToASecondPinnedAddress() throws Exception {
        InetAddress firstAddress = InetAddress.getByName("127.0.0.1");
        InetAddress secondAddress = InetAddress.getByName("127.0.0.2");
        AtomicInteger secondAddressAttempts = new AtomicInteger();
        try (NeverReadingServer first = NeverReadingServer.bindingTo(firstAddress)) {
            URI uri = URI.create("http://pinned.example.test:" + first.port() + "/");
            VerifiedNetworkAddresses pinned =
                    new VerifiedNetworkAddresses(
                            "pinned.example.test",
                            first.port(),
                            List.of(firstAddress, secondAddress));

            FetchOutcome outcome =
                    fetchOnInterruptibleThread(
                            uri,
                            pinned,
                            first::awaitAccepted,
                            largeHeaders(),
                            Optional.of(
                                    delegatingButGuardingSecondAddress(
                                            secondAddress, secondAddressAttempts)));

            assertThat(outcome.thrown())
                    .isInstanceOf(PinnedSocketHttpTransport.TransportInterruptedException.class);
            PinnedSocketHttpTransport.TransportInterruptedException interrupted =
                    (PinnedSocketHttpTransport.TransportInterruptedException) outcome.thrown();
            assertThat(interrupted.transmissionMayHaveStarted())
                    .as("the write was genuinely in flight when interrupted")
                    .isTrue();
            assertThat(outcome.interruptFlagPreserved()).isTrue();
            assertThat(secondAddressAttempts.get()).isEqualTo(0);
        }
    }

    @Test
    void int003InterruptDuringAResponseReadNeverFallsBackToASecondPinnedAddress() throws Exception {
        InetAddress firstAddress = InetAddress.getByName("127.0.0.1");
        InetAddress secondAddress = InetAddress.getByName("127.0.0.2");
        AtomicInteger secondAddressAttempts = new AtomicInteger();
        try (AcceptsRequestThenSilentServer first =
                AcceptsRequestThenSilentServer.bindingTo(firstAddress)) {
            URI uri = URI.create("http://pinned.example.test:" + first.port() + "/");
            VerifiedNetworkAddresses pinned =
                    new VerifiedNetworkAddresses(
                            "pinned.example.test",
                            first.port(),
                            List.of(firstAddress, secondAddress));

            FetchOutcome outcome =
                    fetchOnInterruptibleThread(
                            uri,
                            pinned,
                            first::awaitRequestRead,
                            Map.of(),
                            Optional.of(
                                    delegatingButGuardingSecondAddress(
                                            secondAddress, secondAddressAttempts)));

            assertThat(outcome.thrown())
                    .isInstanceOf(PinnedSocketHttpTransport.TransportInterruptedException.class);
            PinnedSocketHttpTransport.TransportInterruptedException interrupted =
                    (PinnedSocketHttpTransport.TransportInterruptedException) outcome.thrown();
            assertThat(interrupted.transmissionMayHaveStarted())
                    .as("the request was already fully written before the read began")
                    .isTrue();
            assertThat(outcome.interruptFlagPreserved()).isTrue();
            assertThat(secondAddressAttempts.get()).isEqualTo(0);
        }
    }

    @Test
    void intPre001ACallerAlreadyInterruptedBeforeFetchNeverStartsAnyPhysicalNetworkOperation()
            throws Exception {
        InetAddress firstAddress = InetAddress.getByName("127.0.0.1");
        InetAddress secondAddress = InetAddress.getByName("127.0.0.2");
        AtomicInteger connectInvocations = new AtomicInteger();
        PinnedSocketHttpTransport.ISocketConnector neverInvokedConnector =
                (socket, address, timeoutMillis) -> {
                    connectInvocations.incrementAndGet();
                    throw new IOException(
                            "must never be invoked: the caller was already interrupted");
                };
        URI uri = URI.create("http://pinned.example.test:1/");
        VerifiedNetworkAddresses pinned =
                new VerifiedNetworkAddresses(
                        "pinned.example.test", 1, List.of(firstAddress, secondAddress));
        HttpFetchRequest request =
                new HttpFetchRequest(uri, Duration.ofSeconds(30), Map.of(), 10_000);
        PinnedSocketHttpTransport transport =
                new PinnedSocketHttpTransport(
                        IMonotonicClock.systemClock(),
                        (javax.net.ssl.SSLSocketFactory)
                                javax.net.ssl.SSLSocketFactory.getDefault(),
                        neverInvokedConnector);

        AtomicReference<Throwable> thrown = new AtomicReference<>();
        AtomicBoolean interruptedBeforeFetch = new AtomicBoolean();
        AtomicBoolean interruptFlagPreserved = new AtomicBoolean();
        CountDownLatch finished = new CountDownLatch(1);
        Thread fetchThread =
                new Thread(
                        () -> {
                            // Interrupted before fetch() is even called - never via a race against
                            // a concurrently running second thread.
                            Thread.currentThread().interrupt();
                            interruptedBeforeFetch.set(Thread.currentThread().isInterrupted());
                            try {
                                transport.fetch(request, pinned);
                            } catch (Throwable failure) {
                                thrown.set(failure);
                            } finally {
                                interruptFlagPreserved.set(Thread.currentThread().isInterrupted());
                                finished.countDown();
                            }
                        });
        fetchThread.start();
        boolean completed = finished.await(10, TimeUnit.SECONDS);
        assertThat(completed).as("fetch thread terminated").isTrue();

        assertThat(interruptedBeforeFetch.get())
                .as("the thread was already interrupted before fetch() was ever called")
                .isTrue();
        assertThat(thrown.get())
                .isInstanceOf(PinnedSocketHttpTransport.TransportInterruptedException.class);
        PinnedSocketHttpTransport.TransportInterruptedException interrupted =
                (PinnedSocketHttpTransport.TransportInterruptedException) thrown.get();
        assertThat(interrupted.transmissionMayHaveStarted())
                .as("no phase of any attempt ever ran, let alone the request write")
                .isFalse();
        assertThat(interruptFlagPreserved.get()).isTrue();
        assertThat(connectInvocations.get())
                .as(
                        "an already-interrupted caller must never cause a single physical connect"
                                + " attempt - not even one that is immediately cancelled")
                .isEqualTo(0);
    }

    @Test
    void intFallback001InterruptObservedAfterAnOrdinaryFailureBlocksTheNextPinnedAddress()
            throws Exception {
        InetAddress firstAddress = InetAddress.getByName("127.0.0.1");
        InetAddress secondAddress = InetAddress.getByName("127.0.0.2");
        AtomicInteger firstAddressAttempts = new AtomicInteger();
        AtomicInteger secondAddressAttempts = new AtomicInteger();
        InterruptOnNextClockReadAfterArmed clock = new InterruptOnNextClockReadAfterArmed();
        PinnedSocketHttpTransport.ISocketConnector connector =
                (socket, address, timeoutMillis) -> {
                    if (address.getAddress().equals(firstAddress)) {
                        firstAddressAttempts.incrementAndGet();
                        // Simulates the caller becoming interrupted in the narrow window between
                        // this address's ordinary, pre-send connect failure and the transport's
                        // decision to fall back to the next pinned address - armed here, but only
                        // actually applied on the fetching thread's own next clock read, so there
                        // is no real timing race with that thread's own progress.
                        clock.arm();
                        throw new IOException("simulated ordinary pre-send failure");
                    }
                    secondAddressAttempts.incrementAndGet();
                    throw new IOException(
                            "must never be reached: interruption must block the fallback");
                };
        URI uri = URI.create("http://pinned.example.test:1/");
        VerifiedNetworkAddresses pinned =
                new VerifiedNetworkAddresses(
                        "pinned.example.test", 1, List.of(firstAddress, secondAddress));
        HttpFetchRequest request =
                new HttpFetchRequest(uri, Duration.ofSeconds(30), Map.of(), 10_000);
        PinnedSocketHttpTransport transport =
                new PinnedSocketHttpTransport(
                        clock,
                        (javax.net.ssl.SSLSocketFactory)
                                javax.net.ssl.SSLSocketFactory.getDefault(),
                        connector);

        AtomicReference<Throwable> thrown = new AtomicReference<>();
        AtomicBoolean interruptFlagPreserved = new AtomicBoolean();
        CountDownLatch finished = new CountDownLatch(1);
        Thread fetchThread =
                new Thread(
                        () -> {
                            try {
                                transport.fetch(request, pinned);
                            } catch (Throwable failure) {
                                thrown.set(failure);
                            } finally {
                                interruptFlagPreserved.set(Thread.currentThread().isInterrupted());
                                finished.countDown();
                            }
                        });
        fetchThread.start();
        boolean completed = finished.await(10, TimeUnit.SECONDS);
        assertThat(completed).as("fetch thread terminated").isTrue();

        assertThat(thrown.get())
                .isInstanceOf(PinnedSocketHttpTransport.TransportInterruptedException.class);
        assertThat(interruptFlagPreserved.get()).isTrue();
        assertThat(firstAddressAttempts.get())
                .as("the first pinned address was attempted exactly once")
                .isEqualTo(1);
        assertThat(secondAddressAttempts.get())
                .as(
                        "interruption observed after the first address's ordinary failure blocks the"
                                + " second address entirely - it must never be tried")
                .isEqualTo(0);
    }

    /**
     * A clock whose {@link #nanoTime()} interrupts whichever thread next calls it, exactly once,
     * after {@link #arm()} has been called - never before, and never a second time. {@link #arm()}
     * is only ever called from inside a connector invocation that the transport's own {@code
     * Future#get} has not yet returned from; the happens-before edge that delivers that connector's
     * outcome to the fetching thread also makes {@link #arm()}'s effect visible before that same
     * thread's own next clock read, so the interrupt is guaranteed to land no earlier than that
     * connector's failure has been fully processed - without any real timing race.
     */
    private static final class InterruptOnNextClockReadAfterArmed implements IMonotonicClock {
        private final AtomicBoolean armed = new AtomicBoolean();
        private final AtomicBoolean fired = new AtomicBoolean();

        void arm() {
            armed.set(true);
        }

        @Override
        public long nanoTime() {
            if (armed.get() && fired.compareAndSet(false, true)) {
                Thread.currentThread().interrupt();
            }
            return 0L;
        }
    }

    private static FetchOutcome fetchOnInterruptibleThread(
            URI uri, VerifiedNetworkAddresses pinned, ThrowingRunnable awaitPhaseStarted)
            throws Exception {
        return fetchOnInterruptibleThread(
                uri, pinned, awaitPhaseStarted, Map.of(), Optional.empty());
    }

    private static FetchOutcome fetchOnInterruptibleThread(
            URI uri,
            VerifiedNetworkAddresses pinned,
            ThrowingRunnable awaitPhaseStarted,
            Map<String, String> headers)
            throws Exception {
        return fetchOnInterruptibleThread(
                uri, pinned, awaitPhaseStarted, headers, Optional.empty());
    }

    private static FetchOutcome fetchOnInterruptibleThread(
            URI uri,
            VerifiedNetworkAddresses pinned,
            ThrowingRunnable awaitPhaseStarted,
            Map<String, String> headers,
            Optional<PinnedSocketHttpTransport.ISocketConnector> socketConnector)
            throws Exception {
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        AtomicBoolean interruptFlagPreserved = new AtomicBoolean();
        CountDownLatch finished = new CountDownLatch(1);
        HttpFetchRequest request =
                new HttpFetchRequest(uri, Duration.ofSeconds(30), headers, 10_000);
        PinnedSocketHttpTransport transport =
                socketConnector
                        .map(
                                connector ->
                                        new PinnedSocketHttpTransport(
                                                IMonotonicClock.systemClock(),
                                                (javax.net.ssl.SSLSocketFactory)
                                                        javax.net.ssl.SSLSocketFactory.getDefault(),
                                                connector))
                        .orElseGet(
                                () -> new PinnedSocketHttpTransport(IMonotonicClock.systemClock()));
        Thread fetchThread =
                new Thread(
                        () -> {
                            try {
                                transport.fetch(request, pinned);
                            } catch (Throwable failure) {
                                thrown.set(failure);
                            } finally {
                                interruptFlagPreserved.set(Thread.currentThread().isInterrupted());
                                finished.countDown();
                            }
                        });
        fetchThread.start();
        // Deterministic synchronization on the fixture actually having reached the phase under
        // test - never a sleep-and-hope.
        awaitPhaseStarted.run();
        fetchThread.interrupt();
        // A generous safety-net bound only, to keep a genuinely broken test from hanging forever
        // - not the proof of correctness, which is the exception type and state asserted below.
        boolean completed = finished.await(10, TimeUnit.SECONDS);
        assertThat(completed).as("fetch thread terminated after being interrupted").isTrue();
        return new FetchOutcome(thrown.get(), interruptFlagPreserved.get());
    }

    /**
     * A connector that behaves exactly like a real {@code Socket::connect} for any address other
     * than {@code guardedAddress}, and for {@code guardedAddress} itself never touches a socket at
     * all - it only counts the attempt and fails immediately. The seam this suite uses whenever a
     * test needs a second, distinct pinned address that must never really be contacted: the guarded
     * address only ever needs to be a distinct {@link InetAddress} value for this connector's own
     * {@code equals} check, never independently bindable or routable, so a platform on which a
     * second numbered loopback alias cannot be bound (macOS, without an explicit interface alias)
     * is exactly as portable as one where it can (Linux).
     */
    private static PinnedSocketHttpTransport.ISocketConnector delegatingButGuardingSecondAddress(
            InetAddress guardedAddress, AtomicInteger guardedAddressAttempts) {
        return (socket, address, timeoutMillis) -> {
            if (address.getAddress().equals(guardedAddress)) {
                guardedAddressAttempts.incrementAndGet();
                throw new IOException(
                        "must never be reached: the second pinned address must never be"
                                + " contacted");
            }
            socket.connect(address, timeoutMillis);
        };
    }

    private record FetchOutcome(Throwable thrown, boolean interruptFlagPreserved) {}

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private static Map<String, String> largeHeaders() {
        // Large enough to force genuine TCP backpressure against a peer that never reads -
        // reused from PinnedSocketHttpTransportWriteDeadlineTest's own sizing, which found this
        // environment's auto-tuned send-buffer ceiling and sized comfortably past it.
        Map<String, String> headers = new LinkedHashMap<>();
        String filler = "x".repeat(100_000);
        for (int index = 0; index < 100; index++) {
            headers.put("X-Filler-" + index, filler);
        }
        return headers;
    }

    /** Accepts one connection and then never reads or writes anything at all. */
    private static final class SilentServer implements AutoCloseable {
        private final ServerSocket serverSocket;
        private final Thread thread;
        private final CountDownLatch accepted = new CountDownLatch(1);
        private volatile Socket client;

        private SilentServer(ServerSocket serverSocket) {
            this.serverSocket = serverSocket;
            this.thread = new Thread(this::serve);
            this.thread.setDaemon(true);
            this.thread.start();
        }

        static SilentServer bindingTo(InetAddress address) throws IOException {
            return new SilentServer(new ServerSocket(0, 1, address));
        }

        int port() {
            return serverSocket.getLocalPort();
        }

        void awaitAccepted() throws InterruptedException {
            boolean reached = accepted.await(10, TimeUnit.SECONDS);
            assertThat(reached).as("fixture server accepted the connection").isTrue();
        }

        private void serve() {
            try {
                client = serverSocket.accept();
                accepted.countDown();
            } catch (IOException ignored) {
                // Best-effort test server: closed during shutdown.
            }
        }

        @Override
        public void close() throws IOException {
            serverSocket.close();
            if (client != null) {
                client.close();
            }
        }
    }

    /** Accepts one connection with a constrained receive buffer and never reads it. */
    private static final class NeverReadingServer implements AutoCloseable {
        private final ServerSocket serverSocket;
        private final Thread thread;
        private final CountDownLatch accepted = new CountDownLatch(1);
        private volatile Socket client;

        private NeverReadingServer(ServerSocket serverSocket) {
            this.serverSocket = serverSocket;
            this.thread = new Thread(this::serve);
            this.thread.setDaemon(true);
            this.thread.start();
        }

        static NeverReadingServer bindingTo(InetAddress address) throws IOException {
            ServerSocket serverSocket = new ServerSocket();
            serverSocket.setReceiveBufferSize(2_048);
            serverSocket.bind(new java.net.InetSocketAddress(address, 0), 1);
            return new NeverReadingServer(serverSocket);
        }

        int port() {
            return serverSocket.getLocalPort();
        }

        void awaitAccepted() throws InterruptedException {
            boolean reached = accepted.await(10, TimeUnit.SECONDS);
            assertThat(reached).as("fixture server accepted the connection").isTrue();
        }

        private void serve() {
            try {
                client = serverSocket.accept();
                accepted.countDown();
                // Deliberately never reads: holds the connection open with its receive buffer
                // full and undrained, forcing the client's write to genuinely block.
            } catch (IOException ignored) {
                // Best-effort test server: closed during shutdown.
            }
        }

        @Override
        public void close() throws IOException {
            serverSocket.close();
            if (client != null) {
                client.close();
            }
        }
    }

    /** Accepts one connection, reads the full request, then goes silent without responding. */
    private static final class AcceptsRequestThenSilentServer implements AutoCloseable {
        private final ServerSocket serverSocket;
        private final Thread thread;
        private final CountDownLatch requestRead = new CountDownLatch(1);
        private volatile Socket client;

        private AcceptsRequestThenSilentServer(ServerSocket serverSocket) {
            this.serverSocket = serverSocket;
            this.thread = new Thread(this::serve);
            this.thread.setDaemon(true);
            this.thread.start();
        }

        static AcceptsRequestThenSilentServer bindingTo(InetAddress address) throws IOException {
            return new AcceptsRequestThenSilentServer(new ServerSocket(0, 1, address));
        }

        int port() {
            return serverSocket.getLocalPort();
        }

        void awaitRequestRead() throws InterruptedException {
            boolean reached = requestRead.await(10, TimeUnit.SECONDS);
            assertThat(reached).as("fixture server finished reading the request").isTrue();
        }

        private void serve() {
            try {
                client = serverSocket.accept();
                readRequestHeadersRaw(client.getInputStream());
                requestRead.countDown();
                // Deliberately never responds: the client's response read blocks indefinitely.
            } catch (IOException ignored) {
                // Best-effort test server: closed during shutdown.
            }
        }

        @Override
        public void close() throws IOException {
            serverSocket.close();
            if (client != null) {
                client.close();
            }
        }

        private static void readRequestHeadersRaw(java.io.InputStream in) throws IOException {
            int previous = -1;
            int current;
            int consecutiveNewlines = 0;
            while ((current = in.read()) >= 0) {
                if (previous == '\r' && current == '\n') {
                    consecutiveNewlines++;
                    if (consecutiveNewlines == 2) {
                        return;
                    }
                } else if (current != '\r') {
                    consecutiveNewlines = 0;
                }
                previous = current;
            }
        }
    }
}
