package io.webagent4j.crawler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.webagent4j.policy.network.VerifiedNetworkAddresses;
import io.webagent4j.wait.IMonotonicClock;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * HD-WRITE-001: proves the request write/flush phase itself is bounded by the same shared monotonic
 * deadline as every other phase, and that a write which cannot complete is never silently retried
 * against another pinned address.
 *
 * <p>{@code Socket#setSoTimeout} bounds only blocking reads, never writes - a peer that accepts a
 * connection and then never reads anything could otherwise let {@code write()}/{@code flush()}
 * block indefinitely once local and peer TCP buffers fill, well past the request's real deadline.
 * The fixture server constrains its own receive buffer and never drains it, and the request body
 * (headers) is large enough to comfortably exceed both that buffer and typical default socket
 * buffers, so the client's write call genuinely blocks on backpressure rather than merely buffering
 * locally - a real, not simulated, write stall.
 *
 * <p>The real fixture binds only the IPv4 loopback ({@code 127.0.0.1}) - the one loopback address
 * guaranteed bindable without host configuration on every platform this suite runs on, unlike a
 * second {@code 127.0.0.x} alias, which macOS (unlike Linux) does not treat as local without an
 * explicit interface alias. The second pinned address, which must never be contacted at all, is
 * never bound to anything: a connector routes any connect attempt for it straight to a counter
 * instead of the network.
 */
class PinnedSocketHttpTransportWriteDeadlineTest {

    @Test
    void aWriteThatCannotCompleteIsBoundedByTheSharedDeadlineAndNeverRetried() throws Exception {
        InetAddress firstAddress = InetAddress.getByName("127.0.0.1");
        InetAddress secondAddress = InetAddress.getByName("127.0.0.2");
        AtomicInteger secondAddressAttempts = new AtomicInteger();
        try (NeverReadingServer first = NeverReadingServer.bindingTo(firstAddress, 0)) {
            Duration timeout = Duration.ofMillis(400);
            URI uri = URI.create("http://pinned.example.test:" + first.port() + "/");
            HttpFetchRequest request = new HttpFetchRequest(uri, timeout, largeHeaders(), 1_000);
            VerifiedNetworkAddresses pinned =
                    new VerifiedNetworkAddresses(
                            "pinned.example.test",
                            first.port(),
                            List.of(firstAddress, secondAddress));
            PinnedSocketHttpTransport.ISocketConnector connector =
                    (socket, address, timeoutMillis) -> {
                        if (address.getAddress().equals(secondAddress)) {
                            secondAddressAttempts.incrementAndGet();
                            throw new IOException(
                                    "must never be reached: the second pinned address must never"
                                            + " be contacted");
                        }
                        socket.connect(address, timeoutMillis);
                    };

            long startNanos = System.nanoTime();

            assertThatThrownBy(
                            () ->
                                    new PinnedSocketHttpTransport(
                                                    IMonotonicClock.systemClock(),
                                                    (javax.net.ssl.SSLSocketFactory)
                                                            javax.net.ssl.SSLSocketFactory
                                                                    .getDefault(),
                                                    connector)
                                            .fetch(request, pinned))
                    .isInstanceOf(HttpTimeoutException.class);

            Duration elapsed = Duration.ofNanos(System.nanoTime() - startNanos);
            // Generous upper bound on real wall-clock time, well above the 400ms deadline plus
            // watchdog/close overhead, but far below how long an unbounded write() could stall
            // for - this is the same "fails close to the deadline, not the peer's own pace"
            // proof the read-side shared-deadline tests already use.
            assertThat(elapsed).isLessThan(Duration.ofSeconds(5));
            // The write-blocked attempt must never fall back to a second pinned address once
            // transmission may have started - address 2 is never contacted at all.
            assertThat(secondAddressAttempts.get()).isEqualTo(0);
        }
    }

    private static Map<String, String> largeHeaders() {
        // ~10MB of header text: this environment's own auto-tuned TCP send buffer ceiling
        // (/proc/sys/net/ipv4/tcp_wmem) tops out at 4MB, so this payload comfortably exceeds
        // both that ceiling and the fixture's constrained receive buffer, guaranteeing the
        // client's write() call genuinely blocks once the never-reading peer stops accepting
        // any more bytes - never merely buffering locally without ever touching the network.
        Map<String, String> headers = new LinkedHashMap<>();
        String filler = "x".repeat(100_000);
        for (int index = 0; index < 100; index++) {
            headers.put("X-Filler-" + index, filler);
        }
        return headers;
    }

    /**
     * Accepts one connection and then never reads or writes anything, with its own receive buffer
     * deliberately constrained so a large-enough client write is forced to block on real TCP
     * backpressure rather than merely buffering locally.
     */
    private static final class NeverReadingServer implements AutoCloseable {
        private final ServerSocket serverSocket;
        private final Thread thread;
        private volatile Socket accepted;

        private NeverReadingServer(ServerSocket serverSocket) {
            this.serverSocket = serverSocket;
            this.thread = new Thread(this::serve);
            this.thread.setDaemon(true);
            this.thread.start();
        }

        static NeverReadingServer bindingTo(InetAddress address, int port) throws IOException {
            ServerSocket serverSocket = new ServerSocket();
            // Set before bind so the OS applies it to sockets this listener later accepts.
            serverSocket.setReceiveBufferSize(2_048);
            serverSocket.bind(new java.net.InetSocketAddress(address, port), 1);
            return new NeverReadingServer(serverSocket);
        }

        int port() {
            return serverSocket.getLocalPort();
        }

        private void serve() {
            try {
                accepted = serverSocket.accept();
                // Deliberately never reads or writes: holds the connection open with its
                // receive buffer full and undrained, forcing the client's write to block.
            } catch (IOException ignored) {
                // Best-effort test server: closed during shutdown.
            }
        }

        @Override
        public void close() throws IOException {
            serverSocket.close();
            if (accepted != null) {
                accepted.close();
            }
        }
    }
}
