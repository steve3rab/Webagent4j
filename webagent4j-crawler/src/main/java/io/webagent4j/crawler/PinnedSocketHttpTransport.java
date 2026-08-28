package io.webagent4j.crawler;

import io.webagent4j.policy.network.VerifiedNetworkAddresses;
import io.webagent4j.wait.IMonotonicClock;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * Hand-rolled, GET-only HTTP/1.1 (and HTTPS) transport that connects directly to one of a {@link
 * VerifiedNetworkAddresses}'s already-authorized addresses, rather than letting {@code
 * java.net.http.HttpClient} perform its own, independent DNS resolution at connect time.
 *
 * <p>{@code java.net.http.HttpClient} has no public hook to pin a connection to a caller-supplied
 * {@link InetAddress} while still using the destination's hostname for the request line, {@code
 * Host} header, TLS SNI, and certificate hostname verification - this class exists solely to close
 * that gap for the one request shape {@link HttpCrawler} actually needs. Every response is read
 * with {@code Connection: close}; no connection is ever pooled or reused across requests, so a
 * pinned connection can never be silently reused for a different, unauthorized destination.
 *
 * <p>Certificate validation always uses the JVM's default trust store and default enabled TLS
 * protocols (exactly like {@link JavaHttpFetcher}'s {@code HttpClient}-backed path) and is always
 * performed against the destination's logical hostname, never against the physical address that
 * happened to be pinned - the address being pre-authorized never substitutes for a valid
 * certificate matching the hostname.
 */
final class PinnedSocketHttpTransport {

    private static final int HTTP_DEFAULT_PORT = 80;
    private static final int HTTPS_DEFAULT_PORT = 443;
    private static final int MAX_STATUS_LINE_LENGTH = 8_192;
    private static final int MAX_HEADER_LINE_LENGTH = 16_384;
    private static final int MAX_HEADER_COUNT = 200;
    private static final int READ_CHUNK_SIZE = 8_192;

    private final IMonotonicClock clock;
    private final SSLSocketFactory sslSocketFactory;

    /**
     * Creates a transport that validates TLS certificates against the JVM's default trust store.
     */
    PinnedSocketHttpTransport(IMonotonicClock clock) {
        this(clock, (SSLSocketFactory) SSLSocketFactory.getDefault());
    }

    /**
     * Creates a transport validating TLS certificates through {@code sslSocketFactory} instead of
     * the JVM's default trust store - the seam tests use to inject a factory trusting only a
     * specific test certificate, without touching any JVM-global trust configuration.
     */
    PinnedSocketHttpTransport(IMonotonicClock clock, SSLSocketFactory sslSocketFactory) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.sslSocketFactory = Objects.requireNonNull(sslSocketFactory, "sslSocketFactory");
    }

    HttpFetchResult fetch(HttpFetchRequest request, VerifiedNetworkAddresses pinnedAddresses)
            throws IOException {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(pinnedAddresses, "pinnedAddresses");
        URI uri = request.uri();
        boolean tls = tlsForScheme(uri);
        String host = requireHost(uri);
        requireSafeHeaderText(host, "host");
        int port =
                uri.getPort() != -1
                        ? uri.getPort()
                        : (tls ? HTTPS_DEFAULT_PORT : HTTP_DEFAULT_PORT);
        String requestTarget = requestTarget(uri);
        requireSafeHeaderText(requestTarget, "request target");
        byte[] requestBytes = buildRequestBytes(request, host, port, tls, requestTarget);

        long startNanos = clock.nanoTime();
        Deadline deadline = new Deadline(clock, request.timeout());

        IOException lastFailure = null;
        for (InetAddress address : pinnedAddresses.addresses()) {
            try {
                RawResponse raw =
                        attempt(request, host, port, tls, address, requestBytes, deadline);
                Duration elapsed = Duration.ofNanos(clock.nanoTime() - startNanos);
                return new HttpFetchResult(
                        uri,
                        raw.statusCode(),
                        raw.headers(),
                        raw.body(),
                        raw.contentType(),
                        elapsed);
            } catch (HttpTimeoutException timeout) {
                throw timeout;
            } catch (java.net.SocketTimeoutException socketTimeout) {
                // A blocking connect/read exceeded the socket's own SO_TIMEOUT - the same deadline
                // requireTimeBudget() enforces before each blocking call. Reported identically to a
                // pre-check timeout, matching java.net.http.HttpClient's own HttpTimeoutException
                // contract HttpCrawler's retry logic already relies on; never tried against another
                // pinned address once the deadline itself is genuinely exhausted.
                HttpTimeoutException timeout =
                        new HttpTimeoutException("Request to " + uri + " timed out");
                timeout.initCause(socketTimeout);
                throw timeout;
            } catch (IOException failure) {
                lastFailure = failure;
            }
        }
        throw lastFailure;
    }

    private RawResponse attempt(
            HttpFetchRequest request,
            String host,
            int port,
            boolean tls,
            InetAddress address,
            byte[] requestBytes,
            Deadline deadline)
            throws IOException {
        Socket socket = new Socket();
        try {
            socket.connect(
                    new InetSocketAddress(address, port),
                    requireTimeBudget(deadline, request.uri()));
            if (tls) {
                socket = wrapTls(sslSocketFactory, socket, host, port, deadline, request.uri());
            }
            socket.setSoTimeout(requireTimeBudget(deadline, request.uri()));
            socket.getOutputStream().write(requestBytes);
            socket.getOutputStream().flush();
            InputStream in = new BufferedInputStream(socket.getInputStream());
            return readResponse(in, request);
        } finally {
            try {
                socket.close();
            } catch (IOException ignored) {
                // Best-effort cleanup only. Never replace the semantic result/failure above.
            }
        }
    }

    private static SSLSocket wrapTls(
            SSLSocketFactory factory,
            Socket plainSocket,
            String host,
            int port,
            Deadline deadline,
            URI uri)
            throws IOException {
        SSLSocket tlsSocket = (SSLSocket) factory.createSocket(plainSocket, host, port, true);
        SSLParameters parameters = tlsSocket.getSSLParameters();
        // Certificate hostname verification is checked against `host` - the logical destination -
        // never against the physical address the socket actually connected to.
        parameters.setEndpointIdentificationAlgorithm("HTTPS");
        try {
            parameters.setServerNames(List.of(new SNIHostName(host)));
        } catch (IllegalArgumentException notAnSniHostname) {
            // `host` is an IP literal (or otherwise not valid as an SNI ServerName): SNI is simply
            // not applicable to an IP-literal HTTPS destination - endpoint identification above
            // still verifies the certificate against `host`.
        }
        tlsSocket.setSSLParameters(parameters);
        tlsSocket.setSoTimeout(requireTimeBudget(deadline, uri));
        tlsSocket.startHandshake();
        return tlsSocket;
    }

    private static RawResponse readResponse(InputStream in, HttpFetchRequest request)
            throws IOException {
        String statusLine = readLine(in, MAX_STATUS_LINE_LENGTH);
        if (statusLine == null || statusLine.isEmpty()) {
            throw new IOException("Empty response from " + request.uri());
        }
        if (!statusLine.startsWith("HTTP/1.")) {
            throw new IOException("Unsupported HTTP response line: " + statusLine);
        }
        int firstSpace = statusLine.indexOf(' ');
        if (firstSpace < 0) {
            throw new IOException("Malformed status line: " + statusLine);
        }
        int secondSpace = statusLine.indexOf(' ', firstSpace + 1);
        String statusCodeText =
                secondSpace > 0
                        ? statusLine.substring(firstSpace + 1, secondSpace)
                        : statusLine.substring(firstSpace + 1);
        int statusCode;
        try {
            statusCode = Integer.parseInt(statusCodeText.trim());
        } catch (NumberFormatException malformed) {
            throw new IOException("Malformed status code: " + statusLine);
        }

        Map<String, List<String>> headers = new LinkedHashMap<>();
        for (int count = 0; ; count++) {
            if (count >= MAX_HEADER_COUNT) {
                throw new IOException(
                        "Response carried more than " + MAX_HEADER_COUNT + " headers");
            }
            String line = readLine(in, MAX_HEADER_LINE_LENGTH);
            if (line == null) {
                throw new IOException("Connection closed while reading response headers");
            }
            if (line.isEmpty()) {
                break;
            }
            int colon = line.indexOf(':');
            if (colon <= 0) {
                throw new IOException("Malformed response header: " + line);
            }
            String name = line.substring(0, colon).trim();
            String value = line.substring(colon + 1).trim();
            headers.computeIfAbsent(name, key -> new ArrayList<>()).add(value);
        }

        byte[] body = readBody(in, headers, statusCode, request);
        String contentType = firstHeaderIgnoreCase(headers, "Content-Type").orElse("");
        return new RawResponse(statusCode, headers, body, contentType);
    }

    private static byte[] readBody(
            InputStream in,
            Map<String, List<String>> headers,
            int statusCode,
            HttpFetchRequest request)
            throws IOException {
        if (statusCode == 204 || statusCode == 304) {
            return new byte[0];
        }
        Optional<String> transferEncoding = firstHeaderIgnoreCase(headers, "Transfer-Encoding");
        if (transferEncoding.isPresent()
                && transferEncoding.get().toLowerCase(Locale.ROOT).contains("chunked")) {
            return readChunkedBody(in, request);
        }
        Optional<String> contentLengthHeader = firstHeaderIgnoreCase(headers, "Content-Length");
        if (contentLengthHeader.isPresent()) {
            long contentLength;
            try {
                contentLength = Long.parseLong(contentLengthHeader.get().trim());
            } catch (NumberFormatException malformed) {
                throw new IOException("Malformed Content-Length: " + contentLengthHeader.get());
            }
            if (contentLength < 0) {
                throw new IOException("Negative Content-Length: " + contentLength);
            }
            if (contentLength > request.maxResponseBytes()) {
                throw new ResponseTooLargeException(request.uri(), request.maxResponseBytes());
            }
            return readExactly(in, contentLength);
        }
        return readUntilEofBounded(in, request);
    }

    private static byte[] readChunkedBody(InputStream in, HttpFetchRequest request)
            throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        long total = 0;
        while (true) {
            String sizeLine = readLine(in, MAX_HEADER_LINE_LENGTH);
            if (sizeLine == null) {
                throw new IOException("Connection closed while reading a chunk size");
            }
            int semicolon = sizeLine.indexOf(';');
            String sizeText = (semicolon >= 0 ? sizeLine.substring(0, semicolon) : sizeLine).trim();
            long chunkSize;
            try {
                chunkSize = Long.parseLong(sizeText, 16);
            } catch (NumberFormatException malformed) {
                throw new IOException("Malformed chunk size: " + sizeLine);
            }
            if (chunkSize < 0) {
                throw new IOException("Negative chunk size: " + sizeLine);
            }
            if (chunkSize == 0) {
                while (true) {
                    String trailer = readLine(in, MAX_HEADER_LINE_LENGTH);
                    if (trailer == null) {
                        throw new IOException("Connection closed while reading chunk trailers");
                    }
                    if (trailer.isEmpty()) {
                        break;
                    }
                }
                break;
            }
            total += chunkSize;
            if (total > request.maxResponseBytes()) {
                throw new ResponseTooLargeException(request.uri(), request.maxResponseBytes());
            }
            buffer.write(readExactly(in, chunkSize));
            String terminator = readLine(in, 2);
            if (terminator == null || !terminator.isEmpty()) {
                throw new IOException("Malformed chunk terminator");
            }
        }
        return buffer.toByteArray();
    }

    private static byte[] readExactly(InputStream in, long length) throws IOException {
        ByteArrayOutputStream buffer =
                new ByteArrayOutputStream((int) Math.min(length, READ_CHUNK_SIZE));
        byte[] chunk = new byte[READ_CHUNK_SIZE];
        long remaining = length;
        while (remaining > 0) {
            int toRead = (int) Math.min(chunk.length, remaining);
            int read = in.read(chunk, 0, toRead);
            if (read < 0) {
                throw new IOException("Connection closed before Content-Length bytes were read");
            }
            buffer.write(chunk, 0, read);
            remaining -= read;
        }
        return buffer.toByteArray();
    }

    private static byte[] readUntilEofBounded(InputStream in, HttpFetchRequest request)
            throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[READ_CHUNK_SIZE];
        long total = 0;
        int read;
        while ((read = in.read(chunk)) >= 0) {
            total += read;
            if (total > request.maxResponseBytes()) {
                throw new ResponseTooLargeException(request.uri(), request.maxResponseBytes());
            }
            buffer.write(chunk, 0, read);
        }
        return buffer.toByteArray();
    }

    private static String readLine(InputStream in, int maxLength) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        int previous = -1;
        int current;
        while ((current = in.read()) >= 0) {
            if (previous == '\r' && current == '\n') {
                byte[] bytes = line.toByteArray();
                return new String(bytes, 0, bytes.length - 1, StandardCharsets.ISO_8859_1);
            }
            line.write(current);
            if (line.size() > maxLength) {
                throw new IOException("Response line exceeded " + maxLength + " bytes");
            }
            previous = current;
        }
        if (previous == -1 && line.size() == 0) {
            return null;
        }
        throw new IOException("Connection closed before a complete line was received");
    }

    private static Optional<String> firstHeaderIgnoreCase(
            Map<String, List<String>> headers, String name) {
        for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(name) && !entry.getValue().isEmpty()) {
                return Optional.of(entry.getValue().get(0));
            }
        }
        return Optional.empty();
    }

    private static byte[] buildRequestBytes(
            HttpFetchRequest request, String host, int port, boolean tls, String requestTarget)
            throws IOException {
        boolean defaultPort =
                (tls && port == HTTPS_DEFAULT_PORT) || (!tls && port == HTTP_DEFAULT_PORT);
        String hostHeader = defaultPort ? host : host + ":" + port;

        StringBuilder text = new StringBuilder();
        text.append("GET ").append(requestTarget).append(" HTTP/1.1\r\n");
        text.append("Host: ").append(hostHeader).append("\r\n");
        for (Map.Entry<String, String> header : request.headers().entrySet()) {
            String name = header.getKey();
            String value = header.getValue();
            if ("host".equalsIgnoreCase(name) || "connection".equalsIgnoreCase(name)) {
                continue;
            }
            requireSafeHeaderText(name, "header name");
            requireSafeHeaderText(value, "header value");
            text.append(name).append(": ").append(value).append("\r\n");
        }
        text.append("Connection: close\r\n");
        text.append("\r\n");
        return text.toString().getBytes(StandardCharsets.US_ASCII);
    }

    /**
     * Rejects a value that could inject an extra header or request line into this hand-built raw
     * request text (a CR/LF/NUL control character), or that is not representable in the pure-ASCII
     * encoding this class writes requests in.
     */
    private static void requireSafeHeaderText(String value, String what) throws IOException {
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character == '\r' || character == '\n' || character == '\0') {
                throw new IOException("Invalid " + what + ": contains a control character");
            }
            if (character > 127) {
                throw new IOException("Invalid " + what + ": contains a non-ASCII character");
            }
        }
    }

    private static boolean tlsForScheme(URI uri) throws IOException {
        String scheme = uri.getScheme();
        if (scheme == null) {
            throw new IOException("Request URI has no scheme: " + uri);
        }
        return switch (scheme.toLowerCase(Locale.ROOT)) {
            case "https" -> true;
            case "http" -> false;
            default ->
                    throw new IOException("Unsupported scheme for a pinned connection: " + scheme);
        };
    }

    private static String requireHost(URI uri) throws IOException {
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new IOException("Request URI has no host: " + uri);
        }
        return host;
    }

    private static String requestTarget(URI uri) {
        String path = uri.getRawPath();
        if (path == null || path.isEmpty()) {
            path = "/";
        }
        String query = uri.getRawQuery();
        return query == null ? path : path + "?" + query;
    }

    private static int requireTimeBudget(Deadline deadline, URI uri) throws HttpTimeoutException {
        int remaining = deadline.remainingMillis();
        if (remaining <= 0) {
            throw new HttpTimeoutException("Request to " + uri + " timed out");
        }
        return remaining;
    }

    private record RawResponse(
            int statusCode, Map<String, List<String>> headers, byte[] body, String contentType) {}

    private static final class Deadline {
        private final IMonotonicClock clock;
        private final long deadlineNanos;

        Deadline(IMonotonicClock clock, Duration timeout) {
            this.clock = clock;
            this.deadlineNanos = clock.nanoTime() + timeout.toNanos();
        }

        int remainingMillis() {
            long remainingNanos = deadlineNanos - clock.nanoTime();
            long millis = remainingNanos / 1_000_000L;
            if (millis <= 0) {
                return 0;
            }
            return millis > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) millis;
        }
    }
}
