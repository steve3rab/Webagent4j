package io.webagent4j.crawler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.webagent4j.policy.network.VerifiedNetworkAddresses;
import io.webagent4j.wait.IMonotonicClock;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * HT-001 through HT-004: proves {@link PinnedSocketHttpTransport} never silently replays a request
 * to a second pinned address once the first address's request may already have reached its peer.
 *
 * <p>{@link #ht002APostTransmissionReadFailureMustNeverFallBackToAnotherPinnedAddress} is the
 * central regression test: a fake server that fully receives one request and then drops the
 * connection without a response reproduces the exact bug this class's {@code
 * PinnedAttemptFailure}/{@code transmissionMayHaveStarted} boundary exists to close - a second,
 * fully healthy pinned address must receive zero connection attempts, and the failure must surface
 * to the caller rather than being silently absorbed by a retry.
 *
 * <p>Every test's real, physical fixture binds only to the IPv4 loopback ({@code 127.0.0.1}) - the
 * one loopback address guaranteed bindable without host-level configuration on every platform this
 * suite runs on. A second numbered {@code 127.0.0.x} alias binds without any setup on Linux, whose
 * kernel treats the entire {@code 127.0.0.0/8} block as local, but not on macOS, which only
 * recognizes {@code 127.0.0.1} unless a second address is explicitly aliased onto the loopback
 * interface - exactly the {@code BindException: Can't assign requested address} this suite must
 * never risk. Whichever pinned address a given test must never really contact (the second, healthy
 * server in {@link #ht002APostTransmissionReadFailureMustNeverFallBackToAnotherPinnedAddress}
 * through {@link #ht004ASuccessfulFirstAddressNeverConsultsASecondPinnedAddressAtAll}; the
 * unreachable one in {@link
 * #ht001AConnectFailureBeforeAnyTransmissionMayFallBackAndSendExactlyOnePhysicalRequest}) is never
 * bound to anything at all: a connector installed via {@link #fetch(int, List, Duration,
 * PinnedSocketHttpTransport.ISocketConnector)} routes a connect attempt for it straight to a
 * counter or a simulated failure instead of the network, so its {@link InetAddress} value only ever
 * needs to be distinct from the real fixture's, never independently bindable or routable.
 */
class PinnedSocketHttpTransportReplayBoundaryTest {

    @Test
    void ht001AConnectFailureBeforeAnyTransmissionMayFallBackAndSendExactlyOnePhysicalRequest()
            throws Exception {
        // The unreachable address is never bound to anything - the connector below simulates its
        // connect() failing fast, exactly as a real unreachable loopback target would, without
        // needing a second bindable/routable physical address at all.
        InetAddress unreachable = InetAddress.getByName("127.0.0.2");
        InetAddress serverAddress = InetAddress.getByName("127.0.0.1");
        try (CountingHealthyServer server =
                CountingHealthyServer.bindingTo(
                        serverAddress, 0, "HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nok")) {
            HttpFetchResult result =
                    fetch(
                            server.port(),
                            List.of(unreachable, serverAddress),
                            Duration.ofSeconds(5),
                            simulatingConnectFailureFor(unreachable));

            assertThat(result.statusCode()).isEqualTo(200);
            assertThat(server.acceptedConnectionCount()).isEqualTo(1);
        }
    }

    @Test
    void ht002APostTransmissionReadFailureMustNeverFallBackToAnotherPinnedAddress()
            throws Exception {
        InetAddress firstAddress = InetAddress.getByName("127.0.0.1");
        InetAddress secondAddress = InetAddress.getByName("127.0.0.2");
        AtomicInteger secondAddressAttempts = new AtomicInteger();
        try (RequestReceivedThenDroppedServer first =
                RequestReceivedThenDroppedServer.bindingTo(firstAddress, 0)) {
            assertThatThrownBy(
                            () ->
                                    fetch(
                                            first.port(),
                                            List.of(firstAddress, secondAddress),
                                            Duration.ofSeconds(5),
                                            guardingAgainstAnyConnectTo(
                                                    secondAddress, secondAddressAttempts)))
                    .isInstanceOf(IOException.class);

            assertThat(first.awaitRequestReceived()).isTrue();
            // The regression this proof exists for: a second, fully healthy pinned address must
            // never be contacted once the first address's request was fully sent and received -
            // the peer may already have acted on it, so silently retrying elsewhere would risk a
            // duplicate side effect.
            assertThat(secondAddressAttempts.get()).isEqualTo(0);
        }
    }

    @Test
    void ht003APrematurelyClosedResponseAfterTransmissionMustNeverFallBack() throws Exception {
        // A second flavor of post-transmission failure: the server writes a truncated,
        // incomplete status line rather than closing before any bytes at all - still a failure
        // discovered only after the request was fully sent, and must be treated identically.
        InetAddress firstAddress = InetAddress.getByName("127.0.0.1");
        InetAddress secondAddress = InetAddress.getByName("127.0.0.2");
        AtomicInteger secondAddressAttempts = new AtomicInteger();
        try (TruncatedResponseServer first = TruncatedResponseServer.bindingTo(firstAddress, 0)) {
            assertThatThrownBy(
                            () ->
                                    fetch(
                                            first.port(),
                                            List.of(firstAddress, secondAddress),
                                            Duration.ofSeconds(5),
                                            guardingAgainstAnyConnectTo(
                                                    secondAddress, secondAddressAttempts)))
                    .isInstanceOf(IOException.class);

            assertThat(first.awaitRequestReceived()).isTrue();
            assertThat(secondAddressAttempts.get()).isEqualTo(0);
        }
    }

    @Test
    void ht004ASuccessfulFirstAddressNeverConsultsASecondPinnedAddressAtAll() throws Exception {
        InetAddress firstAddress = InetAddress.getByName("127.0.0.1");
        InetAddress secondAddress = InetAddress.getByName("127.0.0.2");
        AtomicInteger secondAddressAttempts = new AtomicInteger();
        try (CountingHealthyServer first =
                CountingHealthyServer.bindingTo(
                        firstAddress, 0, "HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nok")) {
            HttpFetchResult result =
                    fetch(
                            first.port(),
                            List.of(firstAddress, secondAddress),
                            Duration.ofSeconds(5),
                            guardingAgainstAnyConnectTo(secondAddress, secondAddressAttempts));

            assertThat(result.statusCode()).isEqualTo(200);
            assertThat(first.acceptedConnectionCount()).isEqualTo(1);
            assertThat(secondAddressAttempts.get()).isEqualTo(0);
        }
    }

    private static HttpFetchResult fetch(
            int port,
            List<InetAddress> pinnedAddresses,
            Duration timeout,
            PinnedSocketHttpTransport.ISocketConnector socketConnector)
            throws IOException {
        URI uri = URI.create("http://pinned.example.test:" + port + "/");
        HttpFetchRequest request = new HttpFetchRequest(uri, timeout, Map.of(), 1_000);
        VerifiedNetworkAddresses pinned =
                new VerifiedNetworkAddresses("pinned.example.test", port, pinnedAddresses);
        return new PinnedSocketHttpTransport(
                        IMonotonicClock.systemClock(),
                        (javax.net.ssl.SSLSocketFactory)
                                javax.net.ssl.SSLSocketFactory.getDefault(),
                        socketConnector)
                .fetch(request, pinned);
    }

    /**
     * Delegates to a real {@code Socket::connect} for any address other than {@code
     * unreachableAddress}, and fails immediately with an ordinary, pre-transmission {@link
     * ConnectException} for {@code unreachableAddress} itself - never touching a socket for it at
     * all. Lets a test prove pinned-address fallback behavior without needing a second physical
     * address that is genuinely unreachable.
     */
    private static PinnedSocketHttpTransport.ISocketConnector simulatingConnectFailureFor(
            InetAddress unreachableAddress) {
        return (socket, address, timeoutMillis) -> {
            if (address.getAddress().equals(unreachableAddress)) {
                throw new ConnectException("simulated connection refused");
            }
            socket.connect(address, timeoutMillis);
        };
    }

    /**
     * Delegates to a real {@code Socket::connect} for any address other than {@code
     * guardedAddress}, and for {@code guardedAddress} itself never touches a socket at all - it
     * only counts the attempt and fails immediately. Lets a test prove a second pinned address is
     * never contacted without needing that address to be independently bindable or routable.
     */
    private static PinnedSocketHttpTransport.ISocketConnector guardingAgainstAnyConnectTo(
            InetAddress guardedAddress, AtomicInteger guardedAddressAttempts) {
        return (socket, address, timeoutMillis) -> {
            if (address.getAddress().equals(guardedAddress)) {
                guardedAddressAttempts.incrementAndGet();
                throw new IOException(
                        "must never be reached: this pinned address must never be contacted");
            }
            socket.connect(address, timeoutMillis);
        };
    }

    private static String readRequestHeadersRaw(InputStream in) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int previous = -1;
        int current;
        int consecutiveNewlines = 0;
        while ((current = in.read()) >= 0) {
            buffer.write(current);
            if (previous == '\r' && current == '\n') {
                consecutiveNewlines++;
                if (consecutiveNewlines == 2) {
                    break;
                }
            } else if (current != '\r') {
                consecutiveNewlines = 0;
            }
            previous = current;
        }
        return buffer.toString(StandardCharsets.ISO_8859_1);
    }

    /** Accepts one connection, replies with a fixed response, and counts accepted connections. */
    private static final class CountingHealthyServer implements AutoCloseable {
        private final ServerSocket serverSocket;
        private final Thread thread;
        private final AtomicInteger acceptedConnections = new AtomicInteger();

        private CountingHealthyServer(ServerSocket serverSocket, byte[] response) {
            this.serverSocket = serverSocket;
            this.thread = new Thread(() -> serve(response));
            this.thread.setDaemon(true);
            this.thread.start();
        }

        static CountingHealthyServer bindingTo(InetAddress address, int port, String rawResponse)
                throws IOException {
            return new CountingHealthyServer(
                    new ServerSocket(port, 1, address),
                    rawResponse.getBytes(StandardCharsets.ISO_8859_1));
        }

        int port() {
            return serverSocket.getLocalPort();
        }

        int acceptedConnectionCount() {
            return acceptedConnections.get();
        }

        private void serve(byte[] response) {
            try (Socket client = serverSocket.accept()) {
                acceptedConnections.incrementAndGet();
                readRequestHeadersRaw(client.getInputStream());
                OutputStream out = client.getOutputStream();
                out.write(response);
                out.flush();
            } catch (IOException ignored) {
                // Best-effort test server: closed during shutdown, or never contacted at all.
            }
        }

        @Override
        public void close() throws IOException {
            serverSocket.close();
        }
    }

    /** Fully receives one request, then closes without writing any response bytes. */
    private static final class RequestReceivedThenDroppedServer implements AutoCloseable {
        private final ServerSocket serverSocket;
        private final Thread thread;
        private final CountDownLatch requestReceived = new CountDownLatch(1);

        private RequestReceivedThenDroppedServer(ServerSocket serverSocket) {
            this.serverSocket = serverSocket;
            this.thread = new Thread(this::serve);
            this.thread.setDaemon(true);
            this.thread.start();
        }

        static RequestReceivedThenDroppedServer bindingTo(InetAddress address, int port)
                throws IOException {
            return new RequestReceivedThenDroppedServer(new ServerSocket(port, 1, address));
        }

        int port() {
            return serverSocket.getLocalPort();
        }

        boolean awaitRequestReceived() throws InterruptedException {
            return requestReceived.await(5, TimeUnit.SECONDS);
        }

        private void serve() {
            try (Socket client = serverSocket.accept()) {
                readRequestHeadersRaw(client.getInputStream());
                requestReceived.countDown();
                // Deliberately no response bytes: the connection simply closes, simulating a
                // reset discovered only while reading the response - after the request already
                // reached this server in full.
            } catch (IOException ignored) {
                // Best-effort test server.
            }
        }

        @Override
        public void close() throws IOException {
            serverSocket.close();
        }
    }

    /** Fully receives one request, then writes an incomplete status line and closes. */
    private static final class TruncatedResponseServer implements AutoCloseable {
        private final ServerSocket serverSocket;
        private final Thread thread;
        private final CountDownLatch requestReceived = new CountDownLatch(1);

        private TruncatedResponseServer(ServerSocket serverSocket) {
            this.serverSocket = serverSocket;
            this.thread = new Thread(this::serve);
            this.thread.setDaemon(true);
            this.thread.start();
        }

        static TruncatedResponseServer bindingTo(InetAddress address, int port) throws IOException {
            return new TruncatedResponseServer(new ServerSocket(port, 1, address));
        }

        int port() {
            return serverSocket.getLocalPort();
        }

        boolean awaitRequestReceived() throws InterruptedException {
            return requestReceived.await(5, TimeUnit.SECONDS);
        }

        private void serve() {
            try (Socket client = serverSocket.accept()) {
                readRequestHeadersRaw(client.getInputStream());
                requestReceived.countDown();
                OutputStream out = client.getOutputStream();
                out.write("HTTP/1.1 20".getBytes(StandardCharsets.ISO_8859_1));
                out.flush();
            } catch (IOException ignored) {
                // Best-effort test server.
            }
        }

        @Override
        public void close() throws IOException {
            serverSocket.close();
        }
    }
}
