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
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import javax.net.ServerSocketFactory;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLServerSocketFactory;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Proves {@link PinnedSocketHttpTransport} - the raw HTTP/1.1(+TLS) client {@link JavaHttpFetcher}
 * routes a request through whenever a network policy pins a connection to a specific,
 * already-authorized address - connects only to the pinned address while still sending the logical
 * hostname on the request line, {@code Host} header, TLS SNI, and (critically) certificate hostname
 * verification: a certificate that is otherwise trusted but does not match the requested hostname
 * is rejected exactly like an untrusted one, never silently accepted just because the physical
 * address was pre-authorized.
 */
class PinnedSocketHttpTransportTest {

    @Test
    void sendsTheLogicalHostnameOnTheRequestLineAndHostHeaderWhileConnectingToThePinnedAddress()
            throws Exception {
        try (TestHttpServer server =
                TestHttpServer.respondingWith(
                        "HTTP/1.1 200 OK\r\nContent-Length: 5\r\n\r\nhello")) {
            HttpFetchResult result =
                    fetch(server.port(), "/path?x=1", Map.of(), 1_000, Duration.ofSeconds(5));

            assertThat(result.statusCode()).isEqualTo(200);
            assertThat(new String(result.body(), StandardCharsets.UTF_8)).isEqualTo("hello");
            String requestText = server.awaitCapturedRequest();
            assertThat(requestText).startsWith("GET /path?x=1 HTTP/1.1");
            assertThat(requestText).contains("Host: pinned.example.test:" + server.port());
        }
    }

    @Test
    void parsesAContentLengthDelimitedBody() throws Exception {
        try (TestHttpServer server =
                TestHttpServer.respondingWith(
                        "HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nContent-Length: 11\r\n\r\n"
                                + "hello world")) {
            HttpFetchResult result =
                    fetch(server.port(), "/", Map.of(), 1_000, Duration.ofSeconds(5));

            assertThat(new String(result.body(), StandardCharsets.UTF_8)).isEqualTo("hello world");
            assertThat(result.contentType()).isEqualTo("text/plain");
        }
    }

    @Test
    void parsesAChunkedBody() throws Exception {
        try (TestHttpServer server =
                TestHttpServer.respondingWith(
                        "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n"
                                + "5\r\nhello\r\n6\r\n world\r\n0\r\n\r\n")) {
            HttpFetchResult result =
                    fetch(server.port(), "/", Map.of(), 1_000, Duration.ofSeconds(5));

            assertThat(new String(result.body(), StandardCharsets.UTF_8)).isEqualTo("hello world");
        }
    }

    @Test
    void failsWhenTheDeclaredContentLengthExceedsTheByteLimit() throws Exception {
        try (TestHttpServer server =
                TestHttpServer.respondingWith(
                        "HTTP/1.1 200 OK\r\nContent-Length: 1000\r\n\r\n" + "x".repeat(1000))) {
            assertThatThrownBy(() -> fetch(server.port(), "/", Map.of(), 10, Duration.ofSeconds(5)))
                    .isInstanceOf(ResponseTooLargeException.class);
        }
    }

    @Test
    void failsWhenAChunkedBodyExceedsTheByteLimitMidStream() throws Exception {
        try (TestHttpServer server =
                TestHttpServer.respondingWith(
                        "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n"
                                + "a\r\n0123456789\r\n0\r\n\r\n")) {
            assertThatThrownBy(() -> fetch(server.port(), "/", Map.of(), 5, Duration.ofSeconds(5)))
                    .isInstanceOf(ResponseTooLargeException.class);
        }
    }

    @Test
    void aMalformedResponseHeaderNeverLeaksItsRawTextIntoTheExceptionMessage() throws Exception {
        // DG: the response text is entirely attacker/peer-controlled - a malformed header line
        // carrying a secret-shaped marker must never have that marker echoed back into the
        // exception this transport raises.
        String diagnosticSentinel = "DIAGNOSTIC_SENTINEL_582719";
        try (TestHttpServer server =
                TestHttpServer.respondingWith(
                        "HTTP/1.1 200 OK\r\nX-No-Colon-Header-"
                                + diagnosticSentinel
                                + "\r\n\r\n")) {
            assertThatThrownBy(
                            () -> fetch(server.port(), "/", Map.of(), 1_000, Duration.ofSeconds(5)))
                    .isInstanceOf(IOException.class)
                    .satisfies(
                            exception ->
                                    assertThat(exception.getMessage())
                                            .doesNotContain(diagnosticSentinel));
        }
    }

    @Test
    void aMalformedChunkSizeNeverLeaksItsRawTextIntoTheExceptionMessage() throws Exception {
        String diagnosticSentinel = "DIAGNOSTIC_SENTINEL_582719";
        try (TestHttpServer server =
                TestHttpServer.respondingWith(
                        "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n"
                                + diagnosticSentinel
                                + "\r\n")) {
            assertThatThrownBy(
                            () -> fetch(server.port(), "/", Map.of(), 1_000, Duration.ofSeconds(5)))
                    .isInstanceOf(IOException.class)
                    .satisfies(
                            exception ->
                                    assertThat(exception.getMessage())
                                            .doesNotContain(diagnosticSentinel));
        }
    }

    @Test
    void triesTheNextPinnedAddressWhenTheFirstIsUnreachable() throws Exception {
        // The unreachable address is never bound to anything - the connector below simulates its
        // connect() failing fast, exactly as a real unreachable loopback target would, without
        // needing a second bindable/routable physical address. The real server binds only the
        // IPv4 loopback, the one loopback address guaranteed bindable without host configuration
        // on every platform this suite runs on: a second numbered 127.0.0.x alias binds without
        // any setup on Linux, but not on macOS without an explicit interface alias.
        InetAddress unreachable = InetAddress.getByName("127.0.0.2");
        InetAddress serverAddress = InetAddress.getByName("127.0.0.1");
        try (TestHttpServer server =
                TestHttpServer.respondingWithOn(
                        serverAddress, "HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nok")) {
            URI uri = URI.create("http://pinned.example.test:" + server.port() + "/");
            HttpFetchRequest request =
                    new HttpFetchRequest(uri, Duration.ofSeconds(5), Map.of(), 1_000);
            VerifiedNetworkAddresses pinned =
                    new VerifiedNetworkAddresses(
                            "pinned.example.test",
                            server.port(),
                            List.of(unreachable, serverAddress));
            PinnedSocketHttpTransport.ISocketConnector connector =
                    (socket, address, timeoutMillis) -> {
                        if (address.getAddress().equals(unreachable)) {
                            throw new java.net.ConnectException("simulated connection refused");
                        }
                        socket.connect(address, timeoutMillis);
                    };

            HttpFetchResult result =
                    new PinnedSocketHttpTransport(
                                    IMonotonicClock.systemClock(),
                                    (SSLSocketFactory) SSLSocketFactory.getDefault(),
                                    connector)
                            .fetch(request, pinned);

            assertThat(result.statusCode()).isEqualTo(200);
        }
    }

    @Test
    void rejectsAHeaderValueContainingACarriageReturnRatherThanInjectingIt() {
        assertThatThrownBy(
                        () ->
                                fetch(
                                        65535,
                                        "/",
                                        Map.of("X-Evil", "value\r\nX-Injected: yes"),
                                        1_000,
                                        Duration.ofSeconds(1)))
                .isInstanceOf(IOException.class);
    }

    @Test
    void timesOutWhenTheServerNeverResponds() throws Exception {
        try (ServerSocket serverSocket =
                ServerSocketFactory.getDefault()
                        .createServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            Thread acceptor =
                    new Thread(
                            () -> {
                                try (Socket ignored = serverSocket.accept()) {
                                    Thread.sleep(Duration.ofSeconds(10).toMillis());
                                } catch (Exception ignored) {
                                    // test server best-effort
                                }
                            });
            acceptor.setDaemon(true);
            acceptor.start();

            assertThatThrownBy(
                            () ->
                                    fetch(
                                            serverSocket.getLocalPort(),
                                            "/",
                                            Map.of(),
                                            1_000,
                                            Duration.ofMillis(300)))
                    .isInstanceOf(HttpTimeoutException.class);
        }
    }

    @Test
    void timeoutDiag001ATimeoutExceptionNeverLeaksTheRequestUriOrItsSensitiveComponents()
            throws Exception {
        // TIMEOUT-DIAG-001: a URI carrying userinfo (a password), a query parameter, and a
        // fragment - none of which is ever transmitted on the wire, but all of which the raw
        // java.net.URI object itself still carries - must never appear in a timeout exception's
        // message. The old "Request to " + uri + " timed out" pattern would have leaked all
        // three; this transport must never depend on that request-identifying context to explain
        // a timeout, since it can be called directly by any caller, not just HttpCrawler (which
        // separately already renders its own fixed "request timed out" text rather than reading
        // this exception's message).
        String diagnosticSentinel = "DIAGNOSTIC_SENTINEL_582719";
        // The fixture never responds until this test explicitly releases it below - never a
        // fixed sleep standing in for "long enough to outlast the request's own timeout".
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        try (ServerSocket serverSocket =
                ServerSocketFactory.getDefault()
                        .createServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            Thread acceptor =
                    new Thread(
                            () -> {
                                try (Socket ignored = serverSocket.accept()) {
                                    release.await();
                                } catch (InterruptedException interrupted) {
                                    Thread.currentThread().interrupt();
                                } catch (IOException ignored) {
                                    // Best-effort test server: closed during shutdown.
                                }
                            });
            acceptor.setDaemon(true);
            acceptor.start();

            URI uri =
                    URI.create(
                            "http://user:"
                                    + diagnosticSentinel
                                    + "@pinned.example.test:"
                                    + serverSocket.getLocalPort()
                                    + "/path?token="
                                    + diagnosticSentinel
                                    + "#"
                                    + diagnosticSentinel);
            HttpFetchRequest request =
                    new HttpFetchRequest(uri, Duration.ofMillis(300), Map.of(), 1_000);
            VerifiedNetworkAddresses pinned =
                    new VerifiedNetworkAddresses(
                            "pinned.example.test",
                            serverSocket.getLocalPort(),
                            List.of(loopback()));

            assertThatThrownBy(
                            () ->
                                    new PinnedSocketHttpTransport(IMonotonicClock.systemClock())
                                            .fetch(request, pinned))
                    .isInstanceOf(HttpTimeoutException.class)
                    .satisfies(
                            exception -> {
                                String message = exception.getMessage();
                                assertThat(message).doesNotContain(diagnosticSentinel);
                                assertThat(message).doesNotContain("user:");
                                assertThat(message).doesNotContain("?token=");
                                assertThat(message).doesNotContain("#");
                                assertThat(message).doesNotContain(uri.toString());
                            });
        } finally {
            release.countDown();
        }
    }

    @Test
    void aCertificateMatchingTheRequestedHostnameIsAccepted(@TempDir Path tempDir)
            throws Exception {
        TlsFixture fixture = TlsFixture.build(tempDir);
        try (TestHttpsServer server =
                TestHttpsServer.respondingWith(
                        fixture.correctHostServerContext(),
                        "HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nok")) {
            URI uri = URI.create("https://correct-host.test:" + server.port() + "/");
            HttpFetchRequest request =
                    new HttpFetchRequest(uri, Duration.ofSeconds(5), Map.of(), 1_000);
            VerifiedNetworkAddresses pinned =
                    new VerifiedNetworkAddresses(
                            "correct-host.test", server.port(), List.of(loopback()));

            HttpFetchResult result =
                    new PinnedSocketHttpTransport(
                                    IMonotonicClock.systemClock(), fixture.clientSocketFactory())
                            .fetch(request, pinned);

            assertThat(result.statusCode()).isEqualTo(200);
        }
    }

    @Test
    void aTrustedCertificateThatDoesNotMatchTheRequestedHostnameIsRejected(@TempDir Path tempDir)
            throws Exception {
        // The server's certificate is directly trusted by the client's trust store (so ordinary
        // CA/chain validation alone would pass) but was issued for a different hostname than the
        // one being requested - proving hostname verification itself is what rejects this, not
        // merely certificate trust. Never trust a certificate merely because the physical address
        // was pre-authorized: the hostname on the certificate must still match.
        TlsFixture fixture = TlsFixture.build(tempDir);
        try (TestHttpsServer server =
                TestHttpsServer.respondingWith(
                        fixture.mismatchedHostServerContext(),
                        "HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nok")) {
            URI uri = URI.create("https://correct-host.test:" + server.port() + "/");
            HttpFetchRequest request =
                    new HttpFetchRequest(uri, Duration.ofSeconds(5), Map.of(), 1_000);
            VerifiedNetworkAddresses pinned =
                    new VerifiedNetworkAddresses(
                            "correct-host.test", server.port(), List.of(loopback()));

            assertThatThrownBy(
                            () ->
                                    new PinnedSocketHttpTransport(
                                                    IMonotonicClock.systemClock(),
                                                    fixture.clientSocketFactory())
                                            .fetch(request, pinned))
                    .isInstanceOf(IOException.class);
        }
    }

    private static HttpFetchResult fetch(
            int port,
            String path,
            Map<String, String> extraHeaders,
            long maxResponseBytes,
            Duration timeout)
            throws IOException {
        URI uri = URI.create("http://pinned.example.test:" + port + path);
        HttpFetchRequest request =
                new HttpFetchRequest(uri, timeout, extraHeaders, maxResponseBytes);
        VerifiedNetworkAddresses pinned =
                new VerifiedNetworkAddresses("pinned.example.test", port, List.of(loopback()));
        return new PinnedSocketHttpTransport(IMonotonicClock.systemClock()).fetch(request, pinned);
    }

    private static InetAddress loopback() throws IOException {
        return InetAddress.getByName("127.0.0.1");
    }

    /** A single-connection plain-HTTP test server that captures the raw request it received. */
    private static final class TestHttpServer implements AutoCloseable {
        private final ServerSocket serverSocket;
        private final Thread thread;
        private volatile String capturedRequest;
        private final java.util.concurrent.CountDownLatch handled =
                new java.util.concurrent.CountDownLatch(1);

        private TestHttpServer(ServerSocket serverSocket, byte[] responseBytes) {
            this.serverSocket = serverSocket;
            this.thread = new Thread(() -> serve(responseBytes));
            this.thread.setDaemon(true);
            this.thread.start();
        }

        static TestHttpServer respondingWith(String rawResponse) throws IOException {
            return respondingWithOn(InetAddress.getByName("127.0.0.1"), rawResponse);
        }

        static TestHttpServer respondingWithOn(InetAddress bindAddress, String rawResponse)
                throws IOException {
            ServerSocket serverSocket = new ServerSocket(0, 1, bindAddress);
            return new TestHttpServer(
                    serverSocket, rawResponse.getBytes(StandardCharsets.ISO_8859_1));
        }

        int port() {
            return serverSocket.getLocalPort();
        }

        String awaitCapturedRequest() throws InterruptedException {
            handled.await(5, java.util.concurrent.TimeUnit.SECONDS);
            return capturedRequest;
        }

        private void serve(byte[] responseBytes) {
            try (Socket client = serverSocket.accept()) {
                capturedRequest = readRequestHeadersRaw(client.getInputStream());
                OutputStream out = client.getOutputStream();
                out.write(responseBytes);
                out.flush();
            } catch (IOException ignored) {
                // Best-effort test server: a closed serverSocket during shutdown is expected.
            } finally {
                handled.countDown();
            }
        }

        @Override
        public void close() throws IOException {
            serverSocket.close();
        }
    }

    /** A single-connection TLS test server presenting a caller-supplied server identity. */
    private static final class TestHttpsServer implements AutoCloseable {
        private final SSLServerSocket serverSocket;
        private final Thread thread;

        private TestHttpsServer(SSLServerSocket serverSocket, byte[] responseBytes) {
            this.serverSocket = serverSocket;
            this.thread = new Thread(() -> serve(responseBytes));
            this.thread.setDaemon(true);
            this.thread.start();
        }

        static TestHttpsServer respondingWith(SSLContext serverContext, String rawResponse)
                throws IOException {
            SSLServerSocketFactory factory = serverContext.getServerSocketFactory();
            SSLServerSocket serverSocket =
                    (SSLServerSocket)
                            factory.createServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
            return new TestHttpsServer(
                    serverSocket, rawResponse.getBytes(StandardCharsets.ISO_8859_1));
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
                // Best-effort test server, including an expected handshake failure on the
                // hostname-mismatch test: the server side simply observes the connection drop.
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

    /** Generates two self-signed TLS identities and a shared trust store via {@code keytool}. */
    private record TlsFixture(
            SSLContext correctHostServerContext,
            SSLContext mismatchedHostServerContext,
            SSLSocketFactory clientSocketFactory) {

        private static final String STORE_PASSWORD = "changeit";

        static TlsFixture build(Path tempDir) throws Exception {
            Path correctKeystore = tempDir.resolve("correct.p12");
            Path mismatchedKeystore = tempDir.resolve("mismatched.p12");
            Path trustStorePath = tempDir.resolve("trust.p12");
            Path correctCert = tempDir.resolve("correct.cer");
            Path mismatchedCert = tempDir.resolve("mismatched.cer");

            generateSelfSignedIdentity(correctKeystore, "correct", "correct-host.test");
            generateSelfSignedIdentity(mismatchedKeystore, "mismatched", "mismatched-host.test");
            exportCertificate(correctKeystore, "correct", correctCert);
            exportCertificate(mismatchedKeystore, "mismatched", mismatchedCert);
            importCertificate(trustStorePath, "correct", correctCert);
            importCertificate(trustStorePath, "mismatched", mismatchedCert);

            return new TlsFixture(
                    serverContext(correctKeystore),
                    serverContext(mismatchedKeystore),
                    clientFactory(trustStorePath));
        }

        private static void generateSelfSignedIdentity(Path keystore, String alias, String host)
                throws Exception {
            runKeytool(
                    "-genkeypair",
                    "-alias",
                    alias,
                    "-keyalg",
                    "RSA",
                    "-keysize",
                    "2048",
                    "-validity",
                    "2",
                    "-storetype",
                    "PKCS12",
                    "-keystore",
                    keystore.toString(),
                    "-storepass",
                    STORE_PASSWORD,
                    "-keypass",
                    STORE_PASSWORD,
                    "-dname",
                    "CN=" + host,
                    "-ext",
                    "SAN=dns:" + host);
        }

        private static void exportCertificate(Path keystore, String alias, Path certOut)
                throws Exception {
            runKeytool(
                    "-exportcert",
                    "-alias",
                    alias,
                    "-keystore",
                    keystore.toString(),
                    "-storepass",
                    STORE_PASSWORD,
                    "-file",
                    certOut.toString());
        }

        private static void importCertificate(Path trustStore, String alias, Path cert)
                throws Exception {
            runKeytool(
                    "-importcert",
                    "-alias",
                    alias,
                    "-keystore",
                    trustStore.toString(),
                    "-storepass",
                    STORE_PASSWORD,
                    "-file",
                    cert.toString(),
                    "-noprompt");
        }

        private static void runKeytool(String... args) throws Exception {
            java.util.List<String> command = new java.util.ArrayList<>();
            command.add("keytool");
            command.addAll(java.util.Arrays.asList(args));
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            byte[] output = process.getInputStream().readAllBytes();
            boolean finished = process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS);
            if (!finished || process.exitValue() != 0) {
                throw new IllegalStateException(
                        "keytool "
                                + String.join(" ", args)
                                + " failed: "
                                + new String(output, StandardCharsets.UTF_8));
            }
        }

        private static SSLContext serverContext(Path keystorePath) throws Exception {
            KeyStore keyStore = KeyStore.getInstance("PKCS12");
            try (InputStream in = Files.newInputStream(keystorePath)) {
                keyStore.load(in, STORE_PASSWORD.toCharArray());
            }
            KeyManagerFactory keyManagerFactory =
                    KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            keyManagerFactory.init(keyStore, STORE_PASSWORD.toCharArray());
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(keyManagerFactory.getKeyManagers(), null, null);
            return context;
        }

        private static SSLSocketFactory clientFactory(Path trustStorePath) throws Exception {
            KeyStore trustStore = KeyStore.getInstance("PKCS12");
            try (InputStream in = Files.newInputStream(trustStorePath)) {
                trustStore.load(in, STORE_PASSWORD.toCharArray());
            }
            TrustManagerFactory trustManagerFactory =
                    TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            trustManagerFactory.init(trustStore);
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, trustManagerFactory.getTrustManagers(), null);
            return context.getSocketFactory();
        }
    }
}
