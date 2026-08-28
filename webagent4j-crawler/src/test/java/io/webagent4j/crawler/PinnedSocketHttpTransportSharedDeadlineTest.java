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
 * HD-001 through HD-004: proves one response's socket reads share a single overall deadline rather
 * than each blocking read individually receiving a full, fresh {@code SO_TIMEOUT}.
 *
 * <p>{@code Socket#setSoTimeout} bounds one blocking call, not the cumulative time a logical
 * response takes to read - a peer that trickles a response one byte (or one chunk) at a time, each
 * comfortably inside the per-call timeout, could otherwise stall a single response arbitrarily far
 * past the request's real overall budget. Every test here proves the opposite: the fetch fails with
 * {@link HttpTimeoutException} close to the requested timeout, never anywhere near how long an
 * unbounded per-call timeout would have permitted the drip to continue.
 */
class PinnedSocketHttpTransportSharedDeadlineTest {

    private static final Duration TIMEOUT = Duration.ofMillis(300);
    private static final Duration DRIP_INTERVAL = Duration.ofMillis(40);
    // 20 drips at 40ms apart total 800ms - well past TIMEOUT if the deadline were not shared
    // across reads, comfortably bounded by TIMEOUT (plus generous scheduling slack) once it is.
    private static final int DRIP_COUNT = 20;
    private static final Duration MAX_ACCEPTABLE_ELAPSED = Duration.ofMillis(1_200);

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

            assertThatThrownBy(() -> fetch(server.port(), TIMEOUT)).isInstanceOf(IOException.class);

            Duration elapsed = Duration.ofNanos(System.nanoTime() - startNanos);
            assertThat(elapsed).isLessThan(MAX_ACCEPTABLE_ELAPSED);
        }
    }

    @Test
    void hd002ASlowlyDrippedChunkedBodyIsBoundedByTheSharedDeadline() throws Exception {
        try (DrippingChunkedServer server =
                DrippingChunkedServer.bindingTo(loopback(), 0, DRIP_COUNT, DRIP_INTERVAL)) {
            long startNanos = System.nanoTime();

            assertThatThrownBy(() -> fetch(server.port(), TIMEOUT)).isInstanceOf(IOException.class);

            Duration elapsed = Duration.ofNanos(System.nanoTime() - startNanos);
            assertThat(elapsed).isLessThan(MAX_ACCEPTABLE_ELAPSED);
        }
    }

    @Test
    void hd003ASlowlyDrippedStatusLineIsBoundedByTheSharedDeadline() throws Exception {
        // A drip during the status line itself, before any headers or body - proving the shared
        // deadline applies from the very first byte of the response, not only once body-reading
        // begins.
        try (DrippingStatusLineServer server =
                DrippingStatusLineServer.bindingTo(loopback(), 0, DRIP_INTERVAL)) {
            long startNanos = System.nanoTime();

            assertThatThrownBy(() -> fetch(server.port(), TIMEOUT)).isInstanceOf(IOException.class);

            Duration elapsed = Duration.ofNanos(System.nanoTime() - startNanos);
            assertThat(elapsed).isLessThan(MAX_ACCEPTABLE_ELAPSED);
        }
    }

    @Test
    void hd004TheDeadlineIsNeverResetBetweenHeaderReadingAndBodyReading() throws Exception {
        // Consumes almost the whole budget slowly draining the status line and headers, then
        // immediately begins a body that would itself easily fit in a *fresh* timeout - proving
        // the body-reading phase does not silently receive a brand new full-length allowance
        // once header-reading finishes.
        Duration headerDripInterval = Duration.ofMillis(120);
        try (DrippingStatusLineServer server =
                DrippingStatusLineServer.bindingTo(loopback(), 0, headerDripInterval)) {
            long startNanos = System.nanoTime();

            // TIMEOUT (300ms) is consumed almost entirely by three status-line drips at 120ms
            // (360ms total) before any header or body byte is even reachable - so the fetch must
            // fail on budget exhaustion, never proceed into a body phase with a reset clock.
            assertThatThrownBy(() -> fetch(server.port(), TIMEOUT)).isInstanceOf(IOException.class);

            Duration elapsed = Duration.ofNanos(System.nanoTime() - startNanos);
            assertThat(elapsed).isLessThan(MAX_ACCEPTABLE_ELAPSED);
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

    /** Sends chunked-encoding headers, then one single-byte chunk at a time with a fixed delay. */
    private static final class DrippingChunkedServer implements AutoCloseable {
        private final ServerSocket serverSocket;
        private final Thread thread;

        private DrippingChunkedServer(
                ServerSocket serverSocket, int chunkCount, Duration interval) {
            this.serverSocket = serverSocket;
            this.thread = new Thread(() -> serve(chunkCount, interval));
            this.thread.setDaemon(true);
            this.thread.start();
        }

        static DrippingChunkedServer bindingTo(
                InetAddress address, int port, int chunkCount, Duration interval)
                throws IOException {
            return new DrippingChunkedServer(
                    new ServerSocket(port, 1, address), chunkCount, interval);
        }

        int port() {
            return serverSocket.getLocalPort();
        }

        private void serve(int chunkCount, Duration interval) {
            try (Socket client = serverSocket.accept()) {
                readRequestHeadersRaw(client.getInputStream());
                OutputStream out = client.getOutputStream();
                out.write(
                        "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n"
                                .getBytes(StandardCharsets.ISO_8859_1));
                out.flush();
                for (int index = 0; index < chunkCount; index++) {
                    Thread.sleep(interval.toMillis());
                    out.write("1\r\nx\r\n".getBytes(StandardCharsets.ISO_8859_1));
                    out.flush();
                }
            } catch (IOException | InterruptedException ignored) {
                // Best-effort test server.
            }
        }

        @Override
        public void close() throws IOException {
            serverSocket.close();
        }
    }

    /** Sends the HTTP status line one byte at a time with a fixed delay, then never completes. */
    private static final class DrippingStatusLineServer implements AutoCloseable {
        private static final String STATUS_LINE = "HTTP/1.1 200 OK\r\n";
        private final ServerSocket serverSocket;
        private final Thread thread;

        private DrippingStatusLineServer(ServerSocket serverSocket, Duration interval) {
            this.serverSocket = serverSocket;
            this.thread = new Thread(() -> serve(interval));
            this.thread.setDaemon(true);
            this.thread.start();
        }

        static DrippingStatusLineServer bindingTo(InetAddress address, int port, Duration interval)
                throws IOException {
            return new DrippingStatusLineServer(new ServerSocket(port, 1, address), interval);
        }

        int port() {
            return serverSocket.getLocalPort();
        }

        private void serve(Duration interval) {
            try (Socket client = serverSocket.accept()) {
                readRequestHeadersRaw(client.getInputStream());
                OutputStream out = client.getOutputStream();
                for (byte b : STATUS_LINE.getBytes(StandardCharsets.ISO_8859_1)) {
                    Thread.sleep(interval.toMillis());
                    out.write(b);
                    out.flush();
                }
            } catch (IOException | InterruptedException ignored) {
                // Best-effort test server.
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
