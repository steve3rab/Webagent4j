package io.webagent4j.crawler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.webagent4j.policy.network.VerifiedNetworkAddresses;
import io.webagent4j.wait.IMonotonicClock;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * NA-001 through NA-004: proves {@link PinnedSocketHttpTransport} verifies the exact host and port
 * it is about to connect a request to against the host and port {@link VerifiedNetworkAddresses}
 * was actually authorized for - not merely that some address in the pinned set happens to be
 * reachable.
 *
 * <p>{@link #na002AHostMismatchIsDeniedEvenWhenThePinnedAddressIsReachableAndCorrect} is the
 * central proof: the same resolved IP address being reachable, and even genuinely serving the
 * request, must never substitute for the hostname a policy actually authorized. Authority identity
 * is the hostname/port pair a policy reasoned about, never merely "some IP that responds."
 */
class PinnedSocketHttpTransportAuthorityMatchTest {

    @Test
    void na001AnExactHostAndPortMatchIsAllowed() throws Exception {
        try (TestHttpServer server =
                TestHttpServer.respondingWith("HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nok")) {
            HttpFetchResult result =
                    fetch(
                            "pinned.example.test",
                            server.port(),
                            "pinned.example.test",
                            server.port());

            assertThat(result.statusCode()).isEqualTo(200);
        }
    }

    @Test
    void na002AHostMismatchIsDeniedEvenWhenThePinnedAddressIsReachableAndCorrect()
            throws Exception {
        try (TestHttpServer server =
                TestHttpServer.respondingWith("HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nok")) {
            // The pinned addresses genuinely are 127.0.0.1 and the server there genuinely
            // responds - only the authorized hostname differs from the one being requested. The
            // same reachable IP must not paper over that mismatch.
            assertThatThrownBy(
                            () ->
                                    fetch(
                                            "attacker.example.test",
                                            server.port(),
                                            "pinned.example.test",
                                            server.port()))
                    .isInstanceOf(IOException.class);
        }
    }

    @Test
    void na003APortMismatchIsDenied() throws Exception {
        try (TestHttpServer server =
                TestHttpServer.respondingWith("HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nok")) {
            assertThatThrownBy(
                            () ->
                                    fetch(
                                            "pinned.example.test",
                                            server.port(),
                                            "pinned.example.test",
                                            server.port() + 1))
                    .isInstanceOf(IOException.class);
        }
    }

    @Test
    void na004ACanonicallyEquivalentHostStillMatches() throws Exception {
        // Different case and a trailing dot both canonicalize to the same host - this must be
        // allowed exactly like an identical raw string would be, using the same canonicalization
        // a network policy already applied.
        try (TestHttpServer server =
                TestHttpServer.respondingWith("HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nok")) {
            HttpFetchResult result =
                    fetch(
                            "Pinned.Example.Test.",
                            server.port(),
                            "pinned.example.test",
                            server.port());

            assertThat(result.statusCode()).isEqualTo(200);
        }
    }

    private static HttpFetchResult fetch(
            String requestHost, int requestPort, String verifiedHost, int verifiedPort)
            throws IOException {
        URI uri = URI.create("http://" + requestHost + ":" + requestPort + "/");
        HttpFetchRequest request =
                new HttpFetchRequest(uri, Duration.ofSeconds(5), Map.of(), 1_000);
        VerifiedNetworkAddresses pinned =
                new VerifiedNetworkAddresses(verifiedHost, verifiedPort, List.of(loopback()));
        return new PinnedSocketHttpTransport(IMonotonicClock.systemClock()).fetch(request, pinned);
    }

    private static InetAddress loopback() throws IOException {
        return InetAddress.getByName("127.0.0.1");
    }

    /** A single-connection plain-HTTP test server, reused from the sibling transport tests. */
    private static final class TestHttpServer implements AutoCloseable {
        private final ServerSocket serverSocket;
        private final Thread thread;

        private TestHttpServer(ServerSocket serverSocket, byte[] responseBytes) {
            this.serverSocket = serverSocket;
            this.thread = new Thread(() -> serve(responseBytes));
            this.thread.setDaemon(true);
            this.thread.start();
        }

        static TestHttpServer respondingWith(String rawResponse) throws IOException {
            return new TestHttpServer(
                    new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")),
                    rawResponse.getBytes(StandardCharsets.ISO_8859_1));
        }

        int port() {
            return serverSocket.getLocalPort();
        }

        private void serve(byte[] responseBytes) {
            try (Socket client = serverSocket.accept()) {
                readRequestHeadersRaw(client.getInputStream());
                OutputStream out = client.getOutputStream();
                out.write(responseBytes);
                out.flush();
            } catch (IOException ignored) {
                // Best-effort test server: never contacted at all on a denied-authority test.
            }
        }

        @Override
        public void close() throws IOException {
            serverSocket.close();
        }
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
}
