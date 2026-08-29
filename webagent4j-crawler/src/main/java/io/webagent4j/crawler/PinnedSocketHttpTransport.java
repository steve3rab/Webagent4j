package io.webagent4j.crawler;

import io.webagent4j.crawler.api.internal.HttpHeaderValidation;
import io.webagent4j.policy.network.NetworkDestination;
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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
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
    private final ISocketConnector socketConnector;

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
        this(clock, sslSocketFactory, Socket::connect);
    }

    /**
     * Creates a transport connecting through {@code socketConnector} instead of a plain socket's
     * own {@code connect} - the seam a test uses to make the connect phase itself block on a
     * deterministic, test-controlled signal, since (unlike the TLS handshake, request write, or
     * response read) a real, silently-dropped connect attempt gives no peer-observable event a test
     * could otherwise synchronize on.
     */
    PinnedSocketHttpTransport(
            IMonotonicClock clock,
            SSLSocketFactory sslSocketFactory,
            ISocketConnector socketConnector) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.sslSocketFactory = Objects.requireNonNull(sslSocketFactory, "sslSocketFactory");
        this.socketConnector = Objects.requireNonNull(socketConnector, "socketConnector");
    }

    /**
     * The exact shape of {@link Socket#connect(java.net.SocketAddress, int)} - production code
     * always uses that method itself ({@code Socket::connect}); a test substitutes a deterministic,
     * controllable implementation instead.
     */
    @FunctionalInterface
    interface ISocketConnector {
        void connect(Socket socket, InetSocketAddress address, int timeoutMillis)
                throws IOException;
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
        requireVerifiedAuthorityMatchesRequest(tls ? "https" : "http", host, port, pinnedAddresses);
        String requestTarget = requestTarget(uri);
        requireSafeHeaderText(requestTarget, "request target");
        byte[] requestBytes = buildRequestBytes(request, host, port, tls, requestTarget);

        long startNanos = clock.nanoTime();
        Deadline deadline = new Deadline(clock, request.timeout());

        IOException lastFailure = null;
        for (InetAddress address : pinnedAddresses.addresses()) {
            // Checked at the top of every iteration - before the very first address and again
            // before every fallback address - so a caller already interrupted (whether before
            // fetch() was even called, or observed only after an earlier address's ordinary
            // pre-send failure) never causes a new physical attempt to begin at all. This is
            // deliberately distinct from runBoundedOnDeadline's own interruption handling below,
            // which stops an attempt already in flight; this stops one from ever starting.
            requireNotInterrupted();
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
                HttpTimeoutException timeout = newTimeoutException();
                timeout.initCause(socketTimeout);
                throw timeout;
            } catch (TransportInterruptedException interrupted) {
                // Interruption means "stop this operation," never "try a different transport
                // path": it is surfaced immediately as terminal, regardless of whether
                // transmission may already have started - unlike an ordinary connectivity
                // failure, a caller thread asking to stop is never a reason to keep going
                // against a different pinned address.
                throw interrupted;
            } catch (PinnedAttemptFailure failure) {
                if (failure.transmissionMayHaveStarted()) {
                    // The peer may already have received some or all of the request bytes for
                    // this attempt - falling back to a different pinned address here would risk
                    // silently sending the same request a second time. This failure is surfaced
                    // immediately instead, exactly as terminal as it would be for an unpinned,
                    // single-address request.
                    throw failure.original();
                }
                // Nothing was ever written to this address's socket, so no request has been
                // observable to any peer yet - trying the next pinned address is the same
                // single logical request choosing a different equally-authorized destination,
                // never a second physical send of an already-attempted one.
                lastFailure = failure.original();
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
        boolean transmissionMayHaveStarted = false;
        try {
            // Bounded the same way TLS/write/read already are, so an interruption while a
            // connection attempt is still outstanding is observed and made terminal exactly like
            // an interruption in any later phase, rather than sitting unnoticed until connect()
            // itself eventually returns or times out on its own. connect()'s own int-timeout
            // parameter still bounds the call as a second, harmless line of defense; both are
            // derived from the same shared deadline, so neither ever grants a longer budget than
            // the other.
            Socket connectingSocket = socket;
            InetSocketAddress socketAddress = new InetSocketAddress(address, port);
            runBoundedOnDeadline(
                    connectingSocket,
                    deadline,
                    () -> {
                        socketConnector.connect(
                                connectingSocket, socketAddress, requireTimeBudget(deadline));
                        return null;
                    });
            if (tls) {
                socket = wrapTls(sslSocketFactory, socket, host, port, deadline);
            }
            socket.setSoTimeout(requireTimeBudget(deadline));
            // Once the write below is attempted, a failure can never again prove the peer saw
            // nothing: a partial write is indistinguishable from a complete one at this API
            // level, and even a fully successful write only proves bytes reached the local
            // socket buffer, not that the peer failed to act on them. Everything from here
            // onward is therefore "may have been observed by the peer," never "definitely not
            // sent" - see the REQUEST_STARTED boundary this flag models.
            transmissionMayHaveStarted = true;
            // A plain Socket exposes no write-side timeout: SO_TIMEOUT bounds only blocking
            // reads, so a peer applying TCP receive-window backpressure could otherwise stall
            // write()/flush() past the request's own deadline. Bounding this call itself closes
            // that gap - see runBoundedOnDeadline.
            Socket writingSocket = socket;
            runBoundedOnDeadline(
                    writingSocket,
                    deadline,
                    () -> {
                        writingSocket.getOutputStream().write(requestBytes);
                        writingSocket.getOutputStream().flush();
                        return null;
                    });
            InputStream in = new BufferedInputStream(socket.getInputStream());
            ReadState readState = new ReadState(in, socket, deadline);
            // The whole response read is bounded by the same shared deadline and owned socket
            // this call already uses for the write/handshake phases above: a caller thread
            // interrupted while a response is still being read must stop this attempt exactly as
            // terminally as an interruption during the write does, never merely wait out
            // whatever per-read SO_TIMEOUT happens to be active at that moment.
            Socket readingSocket = socket;
            return runBoundedOnDeadline(
                    readingSocket, deadline, () -> readResponse(readState, request));
        } catch (HttpTimeoutException | java.net.SocketTimeoutException timeoutLike) {
            // Handled one level up, identically regardless of phase - never wrapped as a
            // PinnedAttemptFailure, since a deadline/socket timeout is already never retried
            // against another address.
            throw timeoutLike;
        } catch (TransportInterruptedException interrupted) {
            // Finalized here with the transmission-observability this call site actually knows -
            // runBoundedOnDeadline itself has no visibility into whether the write phase has
            // already run, only this method's own local tracking does.
            throw interrupted.withTransmissionMayHaveStarted(transmissionMayHaveStarted);
        } catch (IOException failure) {
            throw new PinnedAttemptFailure(failure, transmissionMayHaveStarted);
        } finally {
            try {
                socket.close();
            } catch (IOException ignored) {
                // Best-effort cleanup only. Never replace the semantic result/failure above.
            }
        }
    }

    /**
     * Internal-only signal from one pinned-address attempt distinguishing "definitely no request
     * bytes were sent" from "the peer may already have observed this request" - the boundary that
     * gates whether {@link #fetch} may retry against a different pinned address. Always unwrapped
     * back to {@link #original()} before crossing this class's boundary, so a caller-visible
     * exception type (for example {@link ResponseTooLargeException}) is never masked by this
     * control-flow-only wrapper.
     */
    private static final class PinnedAttemptFailure extends IOException {
        private final IOException original;
        private final boolean transmissionMayHaveStarted;

        PinnedAttemptFailure(IOException original, boolean transmissionMayHaveStarted) {
            super(original.getMessage(), original);
            this.original = original;
            this.transmissionMayHaveStarted = transmissionMayHaveStarted;
        }

        IOException original() {
            return original;
        }

        boolean transmissionMayHaveStarted() {
            return transmissionMayHaveStarted;
        }
    }

    /**
     * Signals that a blocking network operation was interrupted before it could complete - a
     * distinct, terminal outcome from an ordinary connectivity failure. Interruption means "stop
     * this operation," never "try a different transport path": this type is never wrapped as a
     * {@link PinnedAttemptFailure} and is never eligible for a pinned-address fallback or a
     * crawler-level retry, regardless of {@link #transmissionMayHaveStarted()}.
     *
     * <p>{@link #transmissionMayHaveStarted()} preserves the same truthfulness an ordinary attempt
     * failure already tracks: once a physical request may have reached the peer, this reports
     * {@code true} so a caller downstream never claims certainty that the request's side effect did
     * not happen, purely because the local thread stopped waiting for a response.
     */
    static final class TransportInterruptedException extends IOException {
        private final boolean transmissionMayHaveStarted;

        /**
         * Creates an instance whose caller does not yet know whether transmission may have started;
         * a caller with that ambient knowledge (such as {@link #attempt}) is expected to
         * immediately reclassify it via {@link #withTransmissionMayHaveStarted(boolean)}.
         */
        TransportInterruptedException(InterruptedException cause) {
            this(false, cause);
        }

        TransportInterruptedException(
                boolean transmissionMayHaveStarted, InterruptedException cause) {
            super("blocking network operation interrupted", cause);
            this.transmissionMayHaveStarted = transmissionMayHaveStarted;
        }

        boolean transmissionMayHaveStarted() {
            return transmissionMayHaveStarted;
        }

        /**
         * Returns an equivalent exception carrying {@code value} as {@link
         * #transmissionMayHaveStarted()} - used by a caller (such as {@link #attempt}) that knows
         * the ambient transmission state this exception's own origin point did not.
         */
        TransportInterruptedException withTransmissionMayHaveStarted(boolean value) {
            return new TransportInterruptedException(value, (InterruptedException) getCause());
        }
    }

    private static SSLSocket wrapTls(
            SSLSocketFactory factory, Socket plainSocket, String host, int port, Deadline deadline)
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
        tlsSocket.setSoTimeout(requireTimeBudget(deadline));
        // The handshake itself writes (ClientHello and later flights) as well as reads; SO_TIMEOUT
        // bounds only the read side, so this is bounded the same way the request write is.
        runBoundedOnDeadline(
                tlsSocket,
                deadline,
                () -> {
                    tlsSocket.startHandshake();
                    return null;
                });
        return tlsSocket;
    }

    /**
     * Runs one blocking socket operation - connecting, a TLS handshake, a request write, or a
     * response read - under the same shared monotonic deadline every other phase of this fetch
     * already uses, closing two gaps a plain blocking call otherwise leaves open: {@code
     * Socket#setSoTimeout} bounds only blocking reads, never writes, so a peer applying TCP
     * receive-window backpressure could otherwise stall a write call arbitrarily far past this
     * request's real deadline; and neither a plain {@code Socket#connect} nor a blocking read is
     * interruptible on its own, so without this wrapper an interrupted caller thread would not
     * observe that interruption until the underlying call happened to return on its own.
     *
     * <p>{@code task} runs on a dedicated virtual thread owned entirely by this call: if it does
     * not complete within the deadline's remaining budget, {@code socket} is closed to force it to
     * unblock (a blocked read or write on a closed socket fails promptly), and a {@link
     * HttpTimeoutException} is thrown - identical in every caller-visible respect to a timeout from
     * any other phase, including never being retried against another pinned address. The executor
     * is scoped to this one call (try-with-resources) and is not returned or retained, so no
     * background work outlives this method: either the task already finished before the timeout
     * fires, or the forced socket close unblocks it immediately afterward, so the executor's own
     * close() - which waits for the task to finish - never waits on a still-live blocking
     * operation.
     *
     * <p>An interruption of the calling thread while it waits on {@link Future#get(long, TimeUnit)}
     * is handled distinctly from an ordinary timeout or I/O failure: it is reported as a {@link
     * TransportInterruptedException}, the interrupt flag is restored on this thread, the
     * still-running task is both cancelled and force-unblocked by closing {@code socket} (closure
     * is the mechanism that actually unblocks a plain blocking socket call; cancellation alone
     * cannot), and the caller must treat it as terminal - never retried against another pinned
     * address, never reinterpreted as a transient network failure.
     */
    static <T> T runBoundedOnDeadline(Socket socket, Deadline deadline, IOSupplier<T> task)
            throws IOException {
        int remainingMillis = requireTimeBudget(deadline);
        // Checked immediately before submitting the task - never after - so a caller already
        // interrupted when this phase is about to begin never has that phase actually started on
        // its behalf. This narrows, but cannot fully eliminate, the inherent check-then-act gap
        // between observing the interrupt flag and the executor accepting the task; closing that
        // last sliver would require a deeper synchronization redesign this fix does not attempt.
        requireNotInterrupted();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<T> future = executor.submit(task::get);
            try {
                return future.get(remainingMillis, TimeUnit.MILLISECONDS);
            } catch (TimeoutException expired) {
                future.cancel(true);
                closeQuietly(socket);
                HttpTimeoutException timeout = newTimeoutException();
                timeout.initCause(expired);
                throw timeout;
            } catch (ExecutionException wrapped) {
                Throwable cause = wrapped.getCause();
                if (cause instanceof IOException ioFailure) {
                    throw ioFailure;
                }
                if (cause instanceof RuntimeException runtimeFailure) {
                    throw runtimeFailure;
                }
                if (cause instanceof Error error) {
                    throw error;
                }
                throw new IOException("Blocking socket operation failed", cause);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                future.cancel(true);
                closeQuietly(socket);
                throw new TransportInterruptedException(interrupted);
            }
        }
    }

    /**
     * Fails closed, before any new blocking network operation is submitted or started, when the
     * calling thread is already known to be interrupted - the boundary distinct from (and checked
     * strictly earlier than) {@link #runBoundedOnDeadline}'s own handling of an interruption that
     * arrives only after a phase is already in flight. Uses {@link Thread#isInterrupted()}, never
     * {@link Thread#interrupted()}, since the latter clears the flag as a side effect of reading it
     * - this method only ever observes the flag, never consumes it.
     */
    private static void requireNotInterrupted() throws TransportInterruptedException {
        if (Thread.currentThread().isInterrupted()) {
            throw new TransportInterruptedException(
                    new InterruptedException("network operation interrupted"));
        }
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
            // Best-effort: the only purpose here is unblocking the operation racing this close.
        }
    }

    @FunctionalInterface
    interface IOSupplier<T> {
        T get() throws IOException;
    }

    static RawResponse readResponse(ReadState state, HttpFetchRequest request) throws IOException {
        // The response text below (status line, headers, chunk sizes) is entirely
        // attacker-controlled - it comes verbatim from whatever the connected peer chose to
        // send. None of it is ever embedded in an exception message; every failure here uses a
        // fixed, safe classification instead.
        String statusLine = readLine(state, MAX_STATUS_LINE_LENGTH);
        if (statusLine == null || statusLine.isEmpty()) {
            throw new IOException("empty HTTP response");
        }
        if (!statusLine.startsWith("HTTP/1.")) {
            throw new IOException("unsupported HTTP response version");
        }
        int firstSpace = statusLine.indexOf(' ');
        if (firstSpace < 0) {
            throw new IOException("malformed HTTP status line");
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
            throw new IOException("malformed HTTP status code");
        }

        Map<String, List<String>> headers = new LinkedHashMap<>();
        for (int count = 0; ; count++) {
            if (count >= MAX_HEADER_COUNT) {
                throw new IOException(
                        "Response carried more than " + MAX_HEADER_COUNT + " headers");
            }
            String line = readLine(state, MAX_HEADER_LINE_LENGTH);
            if (line == null) {
                throw new IOException("Connection closed while reading response headers");
            }
            if (line.isEmpty()) {
                break;
            }
            int colon = line.indexOf(':');
            if (colon <= 0) {
                throw new IOException("malformed HTTP response header");
            }
            String name = line.substring(0, colon).trim();
            String value = line.substring(colon + 1).trim();
            headers.computeIfAbsent(name, key -> new ArrayList<>()).add(value);
        }

        byte[] body = readBody(state, headers, statusCode, request);
        String contentType = firstHeaderIgnoreCase(headers, "Content-Type").orElse("");
        return new RawResponse(statusCode, headers, body, contentType);
    }

    private static byte[] readBody(
            ReadState state,
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
            return readChunkedBody(state, request);
        }
        Optional<String> contentLengthHeader = firstHeaderIgnoreCase(headers, "Content-Length");
        if (contentLengthHeader.isPresent()) {
            long contentLength;
            try {
                contentLength = Long.parseLong(contentLengthHeader.get().trim());
            } catch (NumberFormatException malformed) {
                throw new IOException("malformed HTTP Content-Length header");
            }
            if (contentLength < 0) {
                throw new IOException("negative HTTP Content-Length header");
            }
            if (contentLength > request.maxResponseBytes()) {
                throw new ResponseTooLargeException(request.uri(), request.maxResponseBytes());
            }
            return readExactly(state, contentLength);
        }
        return readUntilEofBounded(state, request);
    }

    private static byte[] readChunkedBody(ReadState state, HttpFetchRequest request)
            throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        long total = 0;
        while (true) {
            String sizeLine = readLine(state, MAX_HEADER_LINE_LENGTH);
            if (sizeLine == null) {
                throw new IOException("Connection closed while reading a chunk size");
            }
            int semicolon = sizeLine.indexOf(';');
            String sizeText = (semicolon >= 0 ? sizeLine.substring(0, semicolon) : sizeLine).trim();
            long chunkSize;
            try {
                chunkSize = Long.parseLong(sizeText, 16);
            } catch (NumberFormatException malformed) {
                throw new IOException("malformed HTTP chunk size");
            }
            if (chunkSize < 0) {
                throw new IOException("negative HTTP chunk size");
            }
            if (chunkSize == 0) {
                while (true) {
                    String trailer = readLine(state, MAX_HEADER_LINE_LENGTH);
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
            buffer.write(readExactly(state, chunkSize));
            String terminator = readLine(state, 2);
            if (terminator == null || !terminator.isEmpty()) {
                throw new IOException("Malformed chunk terminator");
            }
        }
        return buffer.toByteArray();
    }

    private static byte[] readExactly(ReadState state, long length) throws IOException {
        ByteArrayOutputStream buffer =
                new ByteArrayOutputStream((int) Math.min(length, READ_CHUNK_SIZE));
        byte[] chunk = new byte[READ_CHUNK_SIZE];
        long remaining = length;
        while (remaining > 0) {
            int toRead = (int) Math.min(chunk.length, remaining);
            // Re-derived from the shared deadline before every blocking read, never the fixed
            // per-call SO_TIMEOUT set once at the start of the response: a slow peer that
            // trickles bytes just under that fixed timeout could otherwise keep resetting its
            // own clock indefinitely, letting one logical response read run far longer than the
            // action's overall shared budget.
            state.refreshTimeout();
            int read = state.in().read(chunk, 0, toRead);
            if (read < 0) {
                throw new IOException("Connection closed before Content-Length bytes were read");
            }
            buffer.write(chunk, 0, read);
            remaining -= read;
        }
        return buffer.toByteArray();
    }

    private static byte[] readUntilEofBounded(ReadState state, HttpFetchRequest request)
            throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[READ_CHUNK_SIZE];
        long total = 0;
        int read;
        while (true) {
            state.refreshTimeout();
            read = state.in().read(chunk);
            if (read < 0) {
                break;
            }
            total += read;
            if (total > request.maxResponseBytes()) {
                throw new ResponseTooLargeException(request.uri(), request.maxResponseBytes());
            }
            buffer.write(chunk, 0, read);
        }
        return buffer.toByteArray();
    }

    private static String readLine(ReadState state, int maxLength) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        int previous = -1;
        int current;
        while (true) {
            // Refreshed before every single byte, not just once per line: SO_TIMEOUT bounds one
            // blocking read call, not the cumulative time spent reading a whole line, so a peer
            // trickling one byte at a time - each individual read() call comfortably inside the
            // per-call timeout - could otherwise stall a single line arbitrarily far past the
            // request's overall shared deadline.
            state.refreshTimeout();
            current = state.in().read();
            if (current < 0) {
                break;
            }
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

    /**
     * Bundles the response {@link InputStream} with everything needed to re-derive and re-apply the
     * socket's blocking-read timeout from the one shared action deadline before every individual
     * blocking read - never a fixed timeout computed once at the start of the response and then
     * left to apply, unchanged, to however many further blocking reads the response happens to
     * require.
     */
    record ReadState(InputStream in, Socket socket, Deadline deadline) {
        /**
         * Re-derives the remaining deadline budget and applies it as this socket's blocking-read
         * timeout - a deadline-sensitive blocking read may only begin once that has actually been
         * installed. If installing it fails, this fails closed by throwing before the caller's next
         * read: an unenforced timeout is never treated as an acceptable substitute for one that was
         * never successfully applied, and the caller must not assume anything about what an
         * unbounded read might do.
         */
        void refreshTimeout() throws IOException {
            try {
                socket.setSoTimeout(requireTimeBudget(deadline));
            } catch (java.net.SocketException failure) {
                throw new IOException("failed to apply the remaining request deadline", failure);
            }
        }
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

        // Defense in depth: request.headers() should already be validated by HttpFetchRequest's
        // own constructor, so this should never actually fire in normal operation. It stays a
        // real, fail-closed check anyway - never removed merely because it is normally
        // unreachable - so this transport still refuses a malformed or framework-controlled
        // header even if some future internal code path ever manages to reach here without going
        // through that constructor.
        requireAllHeadersSafe(request.headers());

        StringBuilder text = new StringBuilder();
        text.append("GET ").append(requestTarget).append(" HTTP/1.1\r\n");
        text.append("Host: ").append(hostHeader).append("\r\n");
        for (Map.Entry<String, String> header : request.headers().entrySet()) {
            text.append(header.getKey()).append(": ").append(header.getValue()).append("\r\n");
        }
        text.append("Connection: close\r\n");
        text.append("\r\n");
        return text.toString().getBytes(StandardCharsets.US_ASCII);
    }

    /**
     * Validates every caller header against the same canonical rule {@link HttpFetchRequest} itself
     * already applies ({@link HttpHeaderValidation}), rather than a second, independently written
     * check that could silently drift out of agreement with it over time. Package-private (not
     * {@code private}) so a test can call it directly with a raw header map, bypassing {@link
     * HttpFetchRequest}'s own constructor entirely, to prove this defense-in-depth layer still
     * fails closed on its own even if nothing upstream had already validated - the seam already
     * unreachable in normal operation, since {@code Host} and {@code Connection} are always
     * validated away by {@link HttpFetchRequest} before headers ever reach here (so this method no
     * longer skips them the way an earlier version of this code silently did).
     */
    static void requireAllHeadersSafe(Map<String, String> headers) throws IOException {
        for (Map.Entry<String, String> header : headers.entrySet()) {
            try {
                HttpHeaderValidation.requireValidHeaderName(header.getKey());
                HttpHeaderValidation.requireNotFrameworkControlled(header.getKey());
                HttpHeaderValidation.requireValidHeaderValue(header.getValue());
            } catch (IllegalArgumentException invalid) {
                throw new IOException(
                        "Invalid or framework-controlled request header rejected at the transport"
                                + " boundary",
                        invalid);
            }
        }
    }

    /**
     * Rejects a value that could inject an extra header or request line into this hand-built raw
     * request text (a CR/LF/NUL control character), or that is not representable in the pure-ASCII
     * encoding this class writes requests in. Used for the request line's host and request-target
     * text, which are not HTTP header name/value pairs and so are not covered by {@link
     * HttpHeaderValidation}.
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
            throw new IOException("request URI has no scheme");
        }
        return switch (scheme.toLowerCase(Locale.ROOT)) {
            case "https" -> true;
            case "http" -> false;
            default ->
                    throw new IOException("Unsupported scheme for a pinned connection: " + scheme);
        };
    }

    /**
     * Verifies that the exact host and port this request is about to connect to are the same host
     * and port {@code pinnedAddresses} was authorized for - using {@link NetworkDestination}'s own
     * canonicalization and default-port rules, the identical ones a network policy already applied
     * when it produced {@code pinnedAddresses}, rather than a second, independently written
     * comparison that could silently drift out of agreement with it over time.
     *
     * <p>The already-resolved physical IP addresses being reachable is never sufficient on its own:
     * a hostname mismatch is rejected here even when every pinned address happens to also be
     * reachable under the requested host's own resolution, since authority identity is the
     * hostname/port pair a policy actually reasoned about, never merely "some IP that responds."
     * Every parameter here has already been validated non-blank/in-range by this method's own
     * caller and by {@link VerifiedNetworkAddresses}'s constructor respectively, so a mismatch
     * exception from constructing either side would indicate a defect in this class rather than
     * caller input - defensive only, not a normally reachable path.
     */
    private static void requireVerifiedAuthorityMatchesRequest(
            String scheme, String requestedHost, int requestedPort, VerifiedNetworkAddresses pinned)
            throws IOException {
        NetworkDestination requested;
        NetworkDestination verified;
        try {
            requested = new NetworkDestination(scheme, requestedHost, requestedPort, false);
            verified = new NetworkDestination(scheme, pinned.host(), pinned.port(), false);
        } catch (RuntimeException malformed) {
            throw new IOException("Network authority could not be evaluated for this request");
        }
        if (!requested.host().equals(verified.host()) || requested.port() != verified.port()) {
            // Never includes the actual host/port values here: this is a security-relevant
            // mismatch, not an ordinary connectivity failure, and the safe diagnostics rule this
            // codebase applies elsewhere to policy denials applies here too.
            throw new IOException(
                    "Requested network authority does not match the verified, pinned authority");
        }
    }

    private static String requireHost(URI uri) throws IOException {
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new IOException("request URI has no host");
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

    static int requireTimeBudget(Deadline deadline) throws HttpTimeoutException {
        int remaining = deadline.remainingMillis();
        if (remaining <= 0) {
            throw newTimeoutException();
        }
        return remaining;
    }

    /**
     * A fixed, safe {@link HttpTimeoutException} carrying no request-specific text at all - in
     * particular, never the request URI, which may carry userinfo (a password), query parameters,
     * or a fragment that must never appear in a caller-visible diagnostic message. This transport
     * can be called directly by code outside {@link HttpCrawler}, which already renders its own
     * fixed "request timed out" text rather than this exception's message; a caller reading {@link
     * Throwable#getMessage()} straight off this exception must see the same safe text.
     */
    private static HttpTimeoutException newTimeoutException() {
        return new HttpTimeoutException("request timed out");
    }

    record RawResponse(
            int statusCode, Map<String, List<String>> headers, byte[] body, String contentType) {}

    /**
     * One shared monotonic deadline for an entire fetch attempt, derived once from an injected
     * {@link IMonotonicClock} - never re-derived from a fresh {@code Instant.now()}-style call - so
     * a fake clock can deterministically simulate budget already having been partially or fully
     * consumed without any real wall-clock waiting.
     */
    static final class Deadline {
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
