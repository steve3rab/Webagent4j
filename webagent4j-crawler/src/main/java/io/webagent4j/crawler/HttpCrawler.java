package io.webagent4j.crawler;

import io.webagent4j.common.RetryPolicy;
import io.webagent4j.crawler.api.CrawlDecision;
import io.webagent4j.crawler.api.CrawlDecisionType;
import io.webagent4j.crawler.api.CrawlFailure;
import io.webagent4j.crawler.api.CrawlFailureType;
import io.webagent4j.crawler.api.CrawlPageProvenance;
import io.webagent4j.crawler.api.CrawlRequest;
import io.webagent4j.crawler.api.CrawlResult;
import io.webagent4j.crawler.api.CrawlStatistics;
import io.webagent4j.crawler.api.CrawlTerminationReason;
import io.webagent4j.crawler.api.CrawledPage;
import io.webagent4j.crawler.api.DiscoveredLink;
import io.webagent4j.crawler.api.ICrawlScopePolicy;
import io.webagent4j.crawler.api.ICrawler;
import io.webagent4j.crawler.api.IUrlNormalizer;
import io.webagent4j.crawler.api.RedirectHop;
import io.webagent4j.crawler.internal.BreadthFirstCrawlFrontier;
import io.webagent4j.crawler.internal.CrawlTask;
import io.webagent4j.crawler.internal.HttpResponseClassifier;
import io.webagent4j.crawler.internal.ICrawlFrontier;
import io.webagent4j.crawler.internal.InMemoryCrawlDeduplicator;
import io.webagent4j.wait.IWaitSleeper;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpTimeoutException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Deterministic, sequential HTTP crawler. One {@link #crawl(CrawlRequest)} call processes the
 * frontier strictly in breadth-first order, resolving redirects and retries itself - {@link
 * IHttpFetcher} performs one HTTP round trip at a time, never an automatic multi-hop follow - so
 * every redirect target is checked against crawl scope before it is ever requested.
 *
 * <p>No concurrency, no {@code Thread.sleep} (retry backoff goes through the injected {@link
 * IWaitSleeper}), and no second parsing pass: the same {@link IHtmlLinkExtractor} result both
 * populates {@link CrawledPage#links()} and drives frontier discovery.
 */
public final class HttpCrawler implements ICrawler {

    private final IHttpFetcher fetcher;
    private final IHtmlLinkExtractor linkExtractor;
    private final ICrawlScopePolicy scopePolicy;
    private final IWaitSleeper sleeper;

    /** Creates a crawler using the real network, jsoup, and default host-scope policy. */
    public HttpCrawler() {
        this(
                new JavaHttpFetcher(),
                new JsoupHtmlLinkExtractor(),
                new HostScopePolicy(),
                IWaitSleeper.parking());
    }

    /**
     * Creates a crawler with every collaborator injected - the seam tests use to avoid the network.
     */
    public HttpCrawler(
            IHttpFetcher fetcher,
            IHtmlLinkExtractor linkExtractor,
            ICrawlScopePolicy scopePolicy,
            IWaitSleeper sleeper) {
        this.fetcher = Objects.requireNonNull(fetcher, "fetcher");
        this.linkExtractor = Objects.requireNonNull(linkExtractor, "linkExtractor");
        this.scopePolicy = Objects.requireNonNull(scopePolicy, "scopePolicy");
        this.sleeper = Objects.requireNonNull(sleeper, "sleeper");
    }

    @Override
    public CrawlResult crawl(CrawlRequest request) {
        Objects.requireNonNull(request, "request");
        return new Session(request).run();
    }

    /** One crawl's mutable state, scoped to a single {@link #crawl(CrawlRequest)} call. */
    private final class Session {

        private final CrawlRequest request;
        private final IUrlNormalizer normalizer;
        private final ICrawlFrontier frontier = new BreadthFirstCrawlFrontier();
        private final InMemoryCrawlDeduplicator dedup = new InMemoryCrawlDeduplicator();
        private final List<CrawledPage> pages = new ArrayList<>();
        private final List<CrawlFailure> failures = new ArrayList<>();
        private final List<DiscoveredLink> rejectedUrls = new ArrayList<>();
        private int discoveredUrls;
        private int fetchedUrls;
        private int redirectCount;
        private int duplicateUrls;
        private long totalBytes;
        private int maxDepthReached;
        private boolean maxPagesHit;
        private boolean stopRequested;

        Session(CrawlRequest request) {
            this.request = request;
            this.normalizer = new DefaultUrlNormalizer(request.queryParameterPolicy());
        }

        CrawlResult run() {
            for (URI seed : request.seeds()) {
                enqueueSeed(seed);
            }
            while (!frontier.isEmpty() && !stopRequested) {
                CrawlTask task = frontier.poll().orElseThrow();
                fetchedUrls++;
                maxDepthReached = Math.max(maxDepthReached, task.depth());
                processTask(task);
            }
            CrawlTerminationReason terminationReason =
                    stopRequested
                            ? CrawlTerminationReason.FATAL_ERROR
                            : (maxPagesHit
                                    ? CrawlTerminationReason.MAX_PAGES_REACHED
                                    : CrawlTerminationReason.COMPLETED);
            CrawlStatistics statistics =
                    new CrawlStatistics(
                            discoveredUrls,
                            fetchedUrls,
                            pages.size(),
                            failures.size(),
                            rejectedUrls.size(),
                            redirectCount,
                            duplicateUrls,
                            totalBytes,
                            maxDepthReached);
            return new CrawlResult(pages, failures, statistics, rejectedUrls, terminationReason);
        }

        private void enqueueSeed(URI seed) {
            URI normalized = normalizer.normalize(seed);
            if (discoveredUrls >= request.maxPages()) {
                maxPagesHit = true;
                return;
            }
            if (!dedup.tryClaim(normalized)) {
                duplicateUrls++;
                return;
            }
            discoveredUrls++;
            frontier.enqueue(new CrawlTask(normalized, 0, normalized, Optional.empty()));
        }

        private void processTask(CrawlTask task) {
            Instant started = Instant.now();
            FetchOutcome outcome = fetchWithRedirects(task);
            Duration fetchDuration = Duration.between(started, Instant.now());
            totalBytes += outcome.bytesRead();
            redirectCount += outcome.chain().size();

            if (outcome.response().isEmpty()) {
                recordFailure(
                        task,
                        outcome.failureType(),
                        outcome.message(),
                        outcome.statusCode(),
                        outcome.cause(),
                        outcome.attempts());
                return;
            }

            HttpFetchResult response = outcome.response().get();
            URI finalUrl = outcome.finalUrl();
            String contentType = contentTypeWithoutParameters(response.contentType());
            if (!request.allowedContentTypes().contains(contentType)) {
                recordFailure(
                        task,
                        CrawlFailureType.UNSUPPORTED_CONTENT_TYPE,
                        "unsupported Content-Type: " + response.contentType(),
                        Optional.of(response.statusCode()),
                        Optional.empty(),
                        1);
                return;
            }

            Charset charset = detectCharset(response.contentType(), response.body());
            String html;
            try {
                html = new String(response.body(), charset);
            } catch (RuntimeException malformed) {
                recordFailure(
                        task,
                        CrawlFailureType.INVALID_CONTENT,
                        "could not decode response body: " + malformed.getMessage(),
                        Optional.of(response.statusCode()),
                        Optional.of(malformed),
                        1);
                return;
            }

            LinkExtractionResult extraction = linkExtractor.extract(html, finalUrl);
            List<DiscoveredLink> pageLinks = processLinks(extraction.links(), task, finalUrl);

            CrawlPageProvenance provenance =
                    new CrawlPageProvenance(
                            task.seedOrigin(),
                            task.discoveredFrom(),
                            task.depth(),
                            task.url(),
                            finalUrl,
                            outcome.chain());
            CrawledPage page =
                    new CrawledPage(
                            task.url(),
                            finalUrl,
                            task.depth(),
                            task.discoveredFrom(),
                            response.statusCode(),
                            response.headers(),
                            contentType,
                            Optional.of(charset),
                            html,
                            extraction.title(),
                            extraction.declaredCanonicalUrl(),
                            pageLinks,
                            outcome.chain(),
                            response.responseBytes(),
                            fetchDuration,
                            provenance);
            pages.add(page);
        }

        private List<DiscoveredLink> processLinks(
                List<ExtractedLink> extractedLinks, CrawlTask task, URI pageUrl) {
            List<DiscoveredLink> pageLinks = new ArrayList<>(extractedLinks.size());
            for (ExtractedLink link : extractedLinks) {
                pageLinks.add(processOneLink(link, task, pageUrl));
            }
            return pageLinks;
        }

        private DiscoveredLink processOneLink(ExtractedLink link, CrawlTask task, URI pageUrl) {
            CrawlDecision scopeDecision =
                    scopePolicy.evaluate(link.resolvedUrl(), pageUrl, request);
            if (!scopeDecision.allowed()) {
                DiscoveredLink rejected = rejectedLink(link, link.resolvedUrl(), scopeDecision);
                rejectedUrls.add(rejected);
                return rejected;
            }

            URI normalizedCandidate;
            try {
                normalizedCandidate = normalizer.normalize(link.resolvedUrl());
            } catch (IllegalArgumentException notNormalizable) {
                DiscoveredLink rejected =
                        rejectedLink(
                                link,
                                link.resolvedUrl(),
                                CrawlDecision.reject(
                                        CrawlDecisionType.REJECT_URL_FILTER,
                                        "could not be normalized: "
                                                + notNormalizable.getMessage()));
                rejectedUrls.add(rejected);
                return rejected;
            }

            int childDepth = task.depth() + 1;
            if (childDepth > request.maxDepth()) {
                DiscoveredLink rejected =
                        rejectedLink(
                                link,
                                normalizedCandidate,
                                CrawlDecision.reject(
                                        CrawlDecisionType.REJECT_DEPTH,
                                        "depth "
                                                + childDepth
                                                + " exceeds maxDepth "
                                                + request.maxDepth()));
                rejectedUrls.add(rejected);
                return rejected;
            }

            if (discoveredUrls >= request.maxPages()) {
                maxPagesHit = true;
                DiscoveredLink rejected =
                        rejectedLink(
                                link,
                                normalizedCandidate,
                                CrawlDecision.reject(
                                        CrawlDecisionType.REJECT_MAX_PAGES,
                                        "maxPages " + request.maxPages() + " already claimed"));
                rejectedUrls.add(rejected);
                return rejected;
            }

            if (!dedup.tryClaim(normalizedCandidate)) {
                duplicateUrls++;
                DiscoveredLink rejected =
                        rejectedLink(
                                link,
                                normalizedCandidate,
                                CrawlDecision.reject(
                                        CrawlDecisionType.REJECT_DUPLICATE,
                                        "already discovered in this crawl"));
                rejectedUrls.add(rejected);
                return rejected;
            }

            discoveredUrls++;
            frontier.enqueue(
                    new CrawlTask(
                            normalizedCandidate,
                            childDepth,
                            task.seedOrigin(),
                            Optional.of(pageUrl)));
            return new DiscoveredLink(
                    link.resolvedUrl(),
                    normalizedCandidate,
                    link.rawHref(),
                    link.anchorText(),
                    link.kind(),
                    true,
                    Optional.empty(),
                    link.documentOrder());
        }

        private DiscoveredLink rejectedLink(
                ExtractedLink link, URI normalizedUrl, CrawlDecision decision) {
            return new DiscoveredLink(
                    link.resolvedUrl(),
                    normalizedUrl,
                    link.rawHref(),
                    link.anchorText(),
                    link.kind(),
                    false,
                    Optional.of(decision),
                    link.documentOrder());
        }

        private void recordFailure(
                CrawlTask task,
                CrawlFailureType type,
                String message,
                Optional<Integer> statusCode,
                Optional<Throwable> cause,
                int attempts) {
            failures.add(
                    new CrawlFailure(
                            task.url(),
                            task.depth(),
                            type,
                            message,
                            statusCode,
                            cause,
                            attempts,
                            task.discoveredFrom()));
            if (request.failFast()) {
                stopRequested = true;
            }
        }

        /** Follows redirects for {@code task}'s URL, one hop at a time, each hop scope-checked. */
        private FetchOutcome fetchWithRedirects(CrawlTask task) {
            URI current = task.url();
            List<RedirectHop> chain = new ArrayList<>();
            Set<URI> visited = new LinkedHashSet<>();
            visited.add(current);
            long bytesRead = 0;

            while (true) {
                RetryOutcome retryOutcome = fetchWithRetries(current);
                bytesRead += retryOutcome.bytesRead();
                if (retryOutcome.result().isEmpty()) {
                    return FetchOutcome.failure(
                            retryOutcome.failureType(),
                            retryOutcome.message(),
                            Optional.empty(),
                            retryOutcome.cause(),
                            retryOutcome.attempts(),
                            bytesRead,
                            chain);
                }
                HttpFetchResult response = retryOutcome.result().get();
                int status = response.statusCode();

                if (HttpResponseClassifier.isRedirect(status)) {
                    if (chain.size() >= request.maxRedirects()) {
                        return FetchOutcome.failure(
                                CrawlFailureType.TOO_MANY_REDIRECTS,
                                "exceeded maxRedirects=" + request.maxRedirects(),
                                Optional.of(status),
                                Optional.empty(),
                                1,
                                bytesRead,
                                chain);
                    }
                    Optional<URI> location = redirectLocation(response, current);
                    if (location.isEmpty()) {
                        return FetchOutcome.failure(
                                CrawlFailureType.INVALID_REDIRECT,
                                "missing or invalid Location header",
                                Optional.of(status),
                                Optional.empty(),
                                1,
                                bytesRead,
                                chain);
                    }
                    URI target = location.get();
                    if (!visited.add(target)) {
                        return FetchOutcome.failure(
                                CrawlFailureType.REDIRECT_LOOP,
                                "redirect loop detected at " + target,
                                Optional.of(status),
                                Optional.empty(),
                                1,
                                bytesRead,
                                chain);
                    }
                    CrawlDecision decision = scopePolicy.evaluate(target, current, request);
                    if (!decision.allowed()) {
                        return FetchOutcome.failure(
                                CrawlFailureType.INVALID_REDIRECT,
                                "redirect target rejected by scope policy: " + decision.reason(),
                                Optional.of(status),
                                Optional.empty(),
                                1,
                                bytesRead,
                                chain);
                    }
                    chain.add(new RedirectHop(current, target, status));
                    current = target;
                    continue;
                }

                if (HttpResponseClassifier.isSuccess(status)) {
                    return FetchOutcome.success(response, current, bytesRead, chain);
                }

                CrawlFailureType type =
                        HttpResponseClassifier.isClientError(status)
                                ? CrawlFailureType.HTTP_CLIENT_ERROR
                                : CrawlFailureType.HTTP_SERVER_ERROR;
                return FetchOutcome.failure(
                        type,
                        "HTTP status " + status,
                        Optional.of(status),
                        Optional.empty(),
                        1,
                        bytesRead,
                        chain);
            }
        }

        /**
         * Retries one hop's fetch per {@link CrawlRequest#retryPolicy()}, sleeping via {@link
         * IWaitSleeper}.
         */
        private RetryOutcome fetchWithRetries(URI url) {
            RetryPolicy policy = request.retryPolicy();
            int attempt = 1;
            long bytesRead = 0;
            while (true) {
                try {
                    HttpFetchResult result = fetcher.fetch(buildFetchRequest(url));
                    bytesRead += result.responseBytes();
                    if (HttpResponseClassifier.isRetryable(
                                    result.statusCode(), request.retryableStatusCodes())
                            && attempt < policy.maxAttempts()) {
                        sleeper.sleep(policy.delayBeforeAttempt(attempt + 1));
                        attempt++;
                        continue;
                    }
                    return RetryOutcome.success(result, bytesRead);
                } catch (HttpTimeoutException timeout) {
                    if (attempt < policy.maxAttempts()) {
                        sleeper.sleep(policy.delayBeforeAttempt(attempt + 1));
                        attempt++;
                        continue;
                    }
                    return RetryOutcome.failure(
                            CrawlFailureType.TIMEOUT,
                            "request timed out",
                            timeout,
                            attempt,
                            bytesRead);
                } catch (ResponseTooLargeException tooLarge) {
                    return RetryOutcome.failure(
                            CrawlFailureType.RESPONSE_TOO_LARGE,
                            tooLarge.getMessage(),
                            tooLarge,
                            attempt,
                            bytesRead);
                } catch (IOException io) {
                    if (request.retryOnIoException() && attempt < policy.maxAttempts()) {
                        sleeper.sleep(policy.delayBeforeAttempt(attempt + 1));
                        attempt++;
                        continue;
                    }
                    return RetryOutcome.failure(
                            CrawlFailureType.NETWORK,
                            String.valueOf(io.getMessage()),
                            io,
                            attempt,
                            bytesRead);
                } catch (RuntimeException opaque) {
                    return RetryOutcome.failure(
                            CrawlFailureType.BACKEND_FAILURE,
                            String.valueOf(opaque.getMessage()),
                            opaque,
                            attempt,
                            bytesRead);
                }
            }
        }

        private HttpFetchRequest buildFetchRequest(URI url) {
            Map<String, String> headers = new LinkedHashMap<>(request.defaultHeaders());
            headers.putIfAbsent("User-Agent", request.userAgent());
            return new HttpFetchRequest(
                    url, request.requestTimeout(), headers, request.maxResponseBytes());
        }

        private Optional<URI> redirectLocation(HttpFetchResult response, URI from) {
            Optional<String> value = firstHeaderValue(response.headers(), "Location");
            if (value.isEmpty() || value.get().isBlank()) {
                return Optional.empty();
            }
            try {
                URI location = new URI(value.get().trim());
                return Optional.of(from.resolve(location));
            } catch (URISyntaxException | IllegalArgumentException malformed) {
                return Optional.empty();
            }
        }
    }

    /** Looks up a header case-insensitively, since HTTP header names are case-insensitive. */
    private static Optional<String> firstHeaderValue(
            Map<String, List<String>> headers, String name) {
        for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(name)
                    && entry.getValue() != null
                    && !entry.getValue().isEmpty()) {
                return Optional.of(entry.getValue().get(0));
            }
        }
        return Optional.empty();
    }

    private static String contentTypeWithoutParameters(String contentTypeHeader) {
        int separator = contentTypeHeader.indexOf(';');
        String base = separator < 0 ? contentTypeHeader : contentTypeHeader.substring(0, separator);
        return base.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * Determines the response body's charset: the {@code Content-Type} header's {@code charset}
     * parameter first, then a byte-order mark, then UTF-8. Deliberately does not sniff an HTML
     * {@code <meta charset>} tag - that would require partially decoding the body before knowing
     * how to decode it, and this phase favors a simple, fully deterministic rule over a heavier
     * one.
     */
    private static Charset detectCharset(String contentTypeHeader, byte[] body) {
        int charsetIndex = contentTypeHeader.toLowerCase(Locale.ROOT).indexOf("charset=");
        if (charsetIndex >= 0) {
            String rest = contentTypeHeader.substring(charsetIndex + "charset=".length());
            int end = rest.indexOf(';');
            String name = (end < 0 ? rest : rest.substring(0, end)).trim().replace("\"", "");
            try {
                if (!name.isEmpty()) {
                    return Charset.forName(name);
                }
            } catch (IllegalArgumentException ignored) {
                // fall through to BOM/default detection
            }
        }
        if (body.length >= 3
                && (body[0] & 0xFF) == 0xEF
                && (body[1] & 0xFF) == 0xBB
                && (body[2] & 0xFF) == 0xBF) {
            return StandardCharsets.UTF_8;
        }
        if (body.length >= 2 && (body[0] & 0xFF) == 0xFF && (body[1] & 0xFF) == 0xFE) {
            return StandardCharsets.UTF_16LE;
        }
        if (body.length >= 2 && (body[0] & 0xFF) == 0xFE && (body[1] & 0xFF) == 0xFF) {
            return StandardCharsets.UTF_16BE;
        }
        return StandardCharsets.UTF_8;
    }

    /** Outcome of following every redirect hop for one task, ending in success or failure. */
    private record FetchOutcome(
            Optional<HttpFetchResult> response,
            URI finalUrl,
            long bytesRead,
            List<RedirectHop> chain,
            CrawlFailureType failureType,
            String message,
            Optional<Integer> statusCode,
            Optional<Throwable> cause,
            int attempts) {

        static FetchOutcome success(
                HttpFetchResult response, URI finalUrl, long bytesRead, List<RedirectHop> chain) {
            return new FetchOutcome(
                    Optional.of(response),
                    finalUrl,
                    bytesRead,
                    List.copyOf(chain),
                    null,
                    "",
                    Optional.empty(),
                    Optional.empty(),
                    0);
        }

        static FetchOutcome failure(
                CrawlFailureType failureType,
                String message,
                Optional<Integer> statusCode,
                Optional<Throwable> cause,
                int attempts,
                long bytesRead,
                List<RedirectHop> chain) {
            return new FetchOutcome(
                    Optional.empty(),
                    null,
                    bytesRead,
                    List.copyOf(chain),
                    failureType,
                    message,
                    statusCode,
                    cause,
                    attempts);
        }
    }

    /** Outcome of retrying one hop's fetch until it succeeds or the retry budget is exhausted. */
    private record RetryOutcome(
            Optional<HttpFetchResult> result,
            long bytesRead,
            CrawlFailureType failureType,
            String message,
            Optional<Throwable> cause,
            int attempts) {

        static RetryOutcome success(HttpFetchResult result, long bytesRead) {
            return new RetryOutcome(Optional.of(result), bytesRead, null, "", Optional.empty(), 0);
        }

        static RetryOutcome failure(
                CrawlFailureType failureType,
                String message,
                Throwable cause,
                int attempts,
                long bytesRead) {
            return new RetryOutcome(
                    Optional.empty(),
                    bytesRead,
                    failureType,
                    message,
                    Optional.of(cause),
                    attempts);
        }
    }
}
