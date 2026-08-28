package io.webagent4j.crawler;

import io.webagent4j.policy.network.VerifiedNetworkAddresses;
import io.webagent4j.wait.IMonotonicClock;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

/**
 * {@link IHttpFetcher} backed by {@code java.net.http.HttpClient}. Redirects are never followed by
 * the client itself ({@link HttpClient.Redirect#NEVER}): {@link HttpCrawler} follows each hop
 * explicitly so it can apply crawl scope to the redirect target before ever requesting it. The
 * response body is read through a bounded subscriber that fails with {@link
 * ResponseTooLargeException} the moment the byte limit would be exceeded, rather than buffering an
 * unbounded response into memory first.
 *
 * <p>No {@code Accept-Encoding} header is sent, so a well-behaved server responds uncompressed;
 * this phase does not implement transparent gzip/deflate decoding, and {@link
 * HttpFetchResult#responseBytes()} is therefore unambiguously the decoded content length.
 *
 * <p>{@link HttpFetchResult#elapsed()} times one round trip against an injected {@link
 * IMonotonicClock}, never {@code Instant.now()} - a wall clock can jump backwards or forwards
 * independently of elapsed time.
 */
public final class JavaHttpFetcher implements IHttpFetcher {

    private final HttpClient client;
    private final IMonotonicClock clock;
    private final PinnedSocketHttpTransport pinnedTransport;

    /** Creates a fetcher with a dedicated, redirect-never {@link HttpClient}. */
    public JavaHttpFetcher() {
        this(IMonotonicClock.systemClock());
    }

    /** Creates a fetcher timing each round trip with {@code clock} rather than a wall clock. */
    public JavaHttpFetcher(IMonotonicClock clock) {
        this.client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        this.clock = Objects.requireNonNull(clock, "clock");
        this.pinnedTransport = new PinnedSocketHttpTransport(this.clock);
    }

    /**
     * Performs one round trip, connecting directly to one of {@code pinnedAddresses} when present
     * instead of letting {@code HttpClient} resolve the destination host itself - {@code
     * HttpClient} has no public hook to pin a connection to a caller-supplied address while still
     * using the hostname for the {@code Host} header, TLS SNI, and certificate verification, so a
     * present {@code pinnedAddresses} routes the request through {@link PinnedSocketHttpTransport}
     * instead. Absent, this is identical to {@link #fetch(HttpFetchRequest)} - the ordinary,
     * unpinned path used whenever no network policy offers a verified address set.
     */
    @Override
    public HttpFetchResult fetch(
            HttpFetchRequest request, Optional<VerifiedNetworkAddresses> pinnedAddresses)
            throws IOException {
        Objects.requireNonNull(pinnedAddresses, "pinnedAddresses");
        if (pinnedAddresses.isPresent()) {
            return pinnedTransport.fetch(request, pinnedAddresses.get());
        }
        return fetch(request);
    }

    @Override
    public HttpFetchResult fetch(HttpFetchRequest request) throws IOException {
        Objects.requireNonNull(request, "request");
        HttpRequest.Builder builder =
                HttpRequest.newBuilder(request.uri()).timeout(request.timeout()).GET();
        request.headers().forEach(builder::header);
        HttpRequest httpRequest = builder.build();

        long startNanos = clock.nanoTime();
        HttpResponse<byte[]> response;
        try {
            response =
                    client.send(
                            httpRequest,
                            info ->
                                    new BoundedByteArraySubscriber(
                                            request.uri(), request.maxResponseBytes()));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while fetching " + request.uri(), interrupted);
        } catch (IOException wrapped) {
            // HttpClient wraps a body subscriber's exceptional completion in its own IOException
            // rather than propagating it directly - unwrap so callers can catch
            // ResponseTooLargeException specifically instead of a generic transport failure.
            if (wrapped.getCause() instanceof ResponseTooLargeException tooLarge) {
                throw tooLarge;
            }
            throw wrapped;
        }
        Duration elapsed = Duration.ofNanos(clock.nanoTime() - startNanos);

        return new HttpFetchResult(
                request.uri(),
                response.statusCode(),
                response.headers().map(),
                response.body(),
                response.headers().firstValue("Content-Type").orElse(""),
                elapsed);
    }

    /**
     * Accumulates response bytes up to {@code limit}, failing the moment one more byte would exceed
     * it - never buffering an oversized response fully before checking its size.
     */
    private static final class BoundedByteArraySubscriber
            implements HttpResponse.BodySubscriber<byte[]> {

        private final URI uri;
        private final long limit;
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        private Flow.Subscription subscription;
        private long received;

        BoundedByteArraySubscriber(URI uri, long limit) {
            this.uri = uri;
            this.limit = limit;
        }

        @Override
        public CompletionStage<byte[]> getBody() {
            return result;
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            subscription.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(List<ByteBuffer> item) {
            for (ByteBuffer chunk : item) {
                int remaining = chunk.remaining();
                if (received + remaining > limit) {
                    subscription.cancel();
                    result.completeExceptionally(new ResponseTooLargeException(uri, limit));
                    return;
                }
                byte[] bytes = new byte[remaining];
                chunk.get(bytes);
                buffer.writeBytes(bytes);
                received += remaining;
            }
        }

        @Override
        public void onError(Throwable throwable) {
            result.completeExceptionally(throwable);
        }

        @Override
        public void onComplete() {
            result.complete(buffer.toByteArray());
        }
    }
}
