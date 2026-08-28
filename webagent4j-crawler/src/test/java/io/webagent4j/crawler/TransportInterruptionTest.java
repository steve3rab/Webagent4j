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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * INT-001 through INT-003: interruption of the calling thread is a terminal outcome, never an
 * ordinary connectivity failure eligible for a pinned-address fallback. Each test blocks a real
 * fetch on a fixture server that deliberately never completes the phase under test, waits (via a
 * {@link CountDownLatch} the fixture itself signals, never a sleep) until that phase has genuinely
 * begun, interrupts the fetching thread, and asserts the resulting exception type, the interrupt
 * flag, and that a second pinned address was never contacted - never on elapsed wall-clock time.
 * INT-004 (a configured crawler retry policy must not retry after interruption) lives in {@link
 * HttpCrawlerTest} instead, next to the rest of that class's retry-classification coverage.
 */
class TransportInterruptionTest {

    @Test
    void int001InterruptDuringTlsHandshakeNeverFallsBackToASecondPinnedAddress() throws Exception {
        InetAddress firstAddress = InetAddress.getByName("127.0.0.4");
        InetAddress secondAddress = InetAddress.getByName("127.0.0.5");
        try (SilentServer first = SilentServer.bindingTo(firstAddress);
                CountingHealthyServer second =
                        CountingHealthyServer.bindingTo(secondAddress, first.port())) {
            URI uri = URI.create("https://pinned.example.test:" + first.port() + "/");
            VerifiedNetworkAddresses pinned =
                    new VerifiedNetworkAddresses(
                            "pinned.example.test",
                            first.port(),
                            List.of(firstAddress, secondAddress));

            FetchOutcome outcome = fetchOnInterruptibleThread(uri, pinned, first::awaitAccepted);

            assertThat(outcome.thrown())
                    .isInstanceOf(PinnedSocketHttpTransport.TransportInterruptedException.class);
            PinnedSocketHttpTransport.TransportInterruptedException interrupted =
                    (PinnedSocketHttpTransport.TransportInterruptedException) outcome.thrown();
            assertThat(interrupted.transmissionMayHaveStarted())
                    .as("TLS handshake precedes the HTTP request write entirely")
                    .isFalse();
            assertThat(outcome.interruptFlagPreserved()).isTrue();
            assertThat(second.acceptedConnectionCount()).isEqualTo(0);
        }
    }

    @Test
    void int002InterruptDuringABlockedWriteNeverFallsBackToASecondPinnedAddress() throws Exception {
        InetAddress firstAddress = InetAddress.getByName("127.0.0.6");
        InetAddress secondAddress = InetAddress.getByName("127.0.0.7");
        try (NeverReadingServer first = NeverReadingServer.bindingTo(firstAddress);
                CountingHealthyServer second =
                        CountingHealthyServer.bindingTo(secondAddress, first.port())) {
            URI uri = URI.create("http://pinned.example.test:" + first.port() + "/");
            VerifiedNetworkAddresses pinned =
                    new VerifiedNetworkAddresses(
                            "pinned.example.test",
                            first.port(),
                            List.of(firstAddress, secondAddress));

            FetchOutcome outcome =
                    fetchOnInterruptibleThread(uri, pinned, first::awaitAccepted, largeHeaders());

            assertThat(outcome.thrown())
                    .isInstanceOf(PinnedSocketHttpTransport.TransportInterruptedException.class);
            PinnedSocketHttpTransport.TransportInterruptedException interrupted =
                    (PinnedSocketHttpTransport.TransportInterruptedException) outcome.thrown();
            assertThat(interrupted.transmissionMayHaveStarted())
                    .as("the write was genuinely in flight when interrupted")
                    .isTrue();
            assertThat(outcome.interruptFlagPreserved()).isTrue();
            assertThat(second.acceptedConnectionCount()).isEqualTo(0);
        }
    }

    @Test
    void int003InterruptDuringAResponseReadNeverFallsBackToASecondPinnedAddress() throws Exception {
        InetAddress firstAddress = InetAddress.getByName("127.0.0.8");
        InetAddress secondAddress = InetAddress.getByName("127.0.0.9");
        try (AcceptsRequestThenSilentServer first =
                        AcceptsRequestThenSilentServer.bindingTo(firstAddress);
                CountingHealthyServer second =
                        CountingHealthyServer.bindingTo(secondAddress, first.port())) {
            URI uri = URI.create("http://pinned.example.test:" + first.port() + "/");
            VerifiedNetworkAddresses pinned =
                    new VerifiedNetworkAddresses(
                            "pinned.example.test",
                            first.port(),
                            List.of(firstAddress, secondAddress));

            FetchOutcome outcome = fetchOnInterruptibleThread(uri, pinned, first::awaitRequestRead);

            assertThat(outcome.thrown())
                    .isInstanceOf(PinnedSocketHttpTransport.TransportInterruptedException.class);
            PinnedSocketHttpTransport.TransportInterruptedException interrupted =
                    (PinnedSocketHttpTransport.TransportInterruptedException) outcome.thrown();
            assertThat(interrupted.transmissionMayHaveStarted())
                    .as("the request was already fully written before the read began")
                    .isTrue();
            assertThat(outcome.interruptFlagPreserved()).isTrue();
            assertThat(second.acceptedConnectionCount()).isEqualTo(0);
        }
    }

    private static FetchOutcome fetchOnInterruptibleThread(
            URI uri, VerifiedNetworkAddresses pinned, ThrowingRunnable awaitPhaseStarted)
            throws Exception {
        return fetchOnInterruptibleThread(uri, pinned, awaitPhaseStarted, Map.of());
    }

    private static FetchOutcome fetchOnInterruptibleThread(
            URI uri,
            VerifiedNetworkAddresses pinned,
            ThrowingRunnable awaitPhaseStarted,
            Map<String, String> headers)
            throws Exception {
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        AtomicBoolean interruptFlagPreserved = new AtomicBoolean();
        CountDownLatch finished = new CountDownLatch(1);
        HttpFetchRequest request =
                new HttpFetchRequest(uri, Duration.ofSeconds(30), headers, 10_000);
        Thread fetchThread =
                new Thread(
                        () -> {
                            try {
                                new PinnedSocketHttpTransport(IMonotonicClock.systemClock())
                                        .fetch(request, pinned);
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

    /** Accepts one connection, replies with a fixed response, and counts accepted connections. */
    private static final class CountingHealthyServer implements AutoCloseable {
        private final ServerSocket serverSocket;
        private final Thread thread;
        private final java.util.concurrent.atomic.AtomicInteger acceptedConnections =
                new java.util.concurrent.atomic.AtomicInteger();

        private CountingHealthyServer(ServerSocket serverSocket) {
            this.serverSocket = serverSocket;
            this.thread = new Thread(this::serve);
            this.thread.setDaemon(true);
            this.thread.start();
        }

        static CountingHealthyServer bindingTo(InetAddress address, int port) throws IOException {
            return new CountingHealthyServer(new ServerSocket(port, 1, address));
        }

        int acceptedConnectionCount() {
            return acceptedConnections.get();
        }

        private void serve() {
            try (Socket client = serverSocket.accept()) {
                acceptedConnections.incrementAndGet();
                client.getOutputStream()
                        .write(
                                "HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nok"
                                        .getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
                client.getOutputStream().flush();
            } catch (IOException ignored) {
                // Best-effort test server: never contacted at all on an interruption test.
            }
        }

        @Override
        public void close() throws IOException {
            serverSocket.close();
        }
    }
}
