package io.webagent4j.crawler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.webagent4j.policy.network.VerifiedNetworkAddresses;
import io.webagent4j.wait.IMonotonicClock;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * HD-001: one real, end-to-end proof that a slowly-trickled response body is bounded by the shared
 * request deadline rather than each blocking read individually receiving a full, fresh {@code
 * SO_TIMEOUT}. {@code Socket#setSoTimeout} bounds one blocking call, not the cumulative time a
 * logical response takes to read - a peer that trickles a response one byte at a time, each
 * comfortably inside the per-call timeout, could otherwise stall a single response arbitrarily far
 * past the request's real overall budget.
 *
 * <p>The deterministic core of this contract - that a phase which already consumed part of the
 * shared budget leaves only the remainder for the next blocking operation, and that a new blocking
 * operation never starts once the budget is exhausted - is proven without any real socket or
 * wall-clock wait in {@code PinnedSocketHttpTransportDeadlineCoreTest} (HD-CORE-002 through
 * HD-CORE-004). This file's single remaining test is the real end-to-end socket confirmation
 * Blocker C still calls for, bounded only by a generous watchdog to catch a broken implementation
 * hanging forever - never a tight elapsed-time threshold used as the semantic proof itself.
 */
class PinnedSocketHttpTransportSharedDeadlineTest {

    private static final Duration TIMEOUT = Duration.ofMillis(300);
    private static final Duration DRIP_INTERVAL = Duration.ofMillis(40);
    // 20 drips at 40ms apart total 800ms - well past TIMEOUT if the deadline were not shared
    // across reads, comfortably bounded by TIMEOUT (plus generous scheduling slack) once it is.
    private static final int DRIP_COUNT = 20;
    // A generous safety-net bound only, to keep a genuinely broken implementation from hanging
    // the test suite forever - not the proof of correctness, which is the specific exception type
    // asserted below.
    private static final Duration WATCHDOG_BOUND = Duration.ofSeconds(10);

    @Test
    void hd001ASlowlyDrippedContentLengthBodyIsBoundedByTheSharedDeadline() throws Exception {
        try (DrippingServer server =
                DrippingServer.bindingTo(
                        loopback(),
                        0,
                        "HTTP/1.1 200 OK\r\nContent-Length: " + DRIP_COUNT + "\r\n\r\n",
                        DRIP_COUNT,
                        DRIP_INTERVAL)) {
            long startNanos = System.nanoTime();

            assertThatThrownBy(() -> fetch(server.port(), TIMEOUT))
                    .isInstanceOf(HttpTimeoutException.class);

            Duration elapsed = Duration.ofNanos(System.nanoTime() - startNanos);
            assertThat(elapsed).isLessThan(WATCHDOG_BOUND);
        }
    }

    private static HttpFetchResult fetch(int port, Duration timeout) throws IOException {
        URI uri = URI.create("http://pinned.example.test:" + port + "/");
        HttpFetchRequest request = new HttpFetchRequest(uri, timeout, Map.of(), 10_000);
        VerifiedNetworkAddresses pinned =
                new VerifiedNetworkAddresses("pinned.example.test", port, List.of(loopback()));
        return new PinnedSocketHttpTransport(IMonotonicClock.systemClock()).fetch(request, pinned);
    }

    private static InetAddress loopback() throws IOException {
        return InetAddress.getByName("127.0.0.1");
    }

    /** Sends a fixed header block, then the response body one byte at a time with a fixed delay. */
    private static final class DrippingServer implements AutoCloseable {
        private final ServerSocket serverSocket;
        private final Thread thread;

        private DrippingServer(
                ServerSocket serverSocket, String headerBlock, int bodyBytes, Duration interval) {
            this.serverSocket = serverSocket;
            this.thread = new Thread(() -> serve(headerBlock, bodyBytes, interval));
            this.thread.setDaemon(true);
            this.thread.start();
        }

        static DrippingServer bindingTo(
                InetAddress address, int port, String headerBlock, int bodyBytes, Duration interval)
                throws IOException {
            return new DrippingServer(
                    new ServerSocket(port, 1, address), headerBlock, bodyBytes, interval);
        }

        int port() {
            return serverSocket.getLocalPort();
        }

        private void serve(String headerBlock, int bodyBytes, Duration interval) {
            try (Socket client = serverSocket.accept()) {
                readRequestHeadersRaw(client.getInputStream());
                OutputStream out = client.getOutputStream();
                out.write(headerBlock.getBytes(StandardCharsets.ISO_8859_1));
                out.flush();
                for (int index = 0; index < bodyBytes; index++) {
                    Thread.sleep(interval.toMillis());
                    out.write('x');
                    out.flush();
                }
            } catch (IOException | InterruptedException ignored) {
                // Best-effort test server: the client is expected to abandon the connection once
                // its own deadline expires, before this loop finishes.
            }
        }

        @Override
        public void close() throws IOException {
            serverSocket.close();
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
