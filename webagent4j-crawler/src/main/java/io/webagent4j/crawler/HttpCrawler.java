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
import io.webagent4j.policy.PolicyDecision;
import io.webagent4j.policy.network.INetworkPolicy;
import io.webagent4j.policy.network.NetworkCheckPhase;
import io.webagent4j.policy.network.NetworkDestination;
import io.webagent4j.policy.network.NetworkPolicyContext;
import io.webagent4j.policy.network.NetworkRequestKind;
import io.webagent4j.wait.IMonotonicClock;
import io.webagent4j.wait.IWaitSleeper;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpTimeoutException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
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
 * every redirect target is checked against crawl scope, and claimed against the crawl-wide fetch
 * identity budget, before it is ever requested.
 *
 * <p>No concurrency, no {@code Thread.sleep} (retry backoff goes through the injected {@link
 * IWaitSleeper}) and no {@code Instant.now()} (durations go through the injected {@link
 * IMonotonicClock}), and no second parsing pass: the same {@link IHtmlLinkExtractor} result both
 * populates {@link CrawledPage#links()} and drives frontier discovery.
 *
 * <h2>Fetch identity</h2>
 *
 * <p>{@link CrawlRequest#maxPages()} bounds the number of distinct normalized URLs for which a real
 * HTTP request is ever started - a task's own URL and every redirect hop it follows alike, retries
 * of the same URL never counted twice. This is enforced by one central check, {@code
 * Session#claimFetchIdentity}, immediately before every such request; a redirect can never bypass
 * the limit by escaping the URL that discovered it, and a redirect converging on an already
 * -fetched identity is never silently re-fetched.
 */
public final class HttpCrawler implements ICrawler {

    private final IHttpFetcher fetcher;
    private final IHtmlLinkExtractor linkExtractor;
    private final ICrawlScopePolicy scopePolicy;
    private final IWaitSleeper sleeper;
    private final IMonotonicClock clock;
    private final Optional<INetworkPolicy> networkPolicy;

    /**
     * Creates a crawler using the real network, jsoup, the default host-scope policy, and a real
     * clock.
     */
    public HttpCrawler() {
        this(
                new JavaHttpFetcher(),
                new JsoupHtmlLinkExtractor(),
                new HostScopePolicy(),
                IWaitSleeper.parking(),
                IMonotonicClock.systemClock());
    }

    /**
     * Creates a crawler with every collaborator injected except the clock, which defaults to {@link
     * IMonotonicClock#systemClock()} - the seam tests use to avoid the network and real sleeping.
     */
    public HttpCrawler(
            IHttpFetcher fetcher,
            IHtmlLinkExtractor linkExtractor,
            ICrawlScopePolicy scopePolicy,
            IWaitSleeper sleeper) {
        this(fetcher, linkExtractor, scopePolicy, sleeper, IMonotonicClock.systemClock());
    }

    /**
     * Creates a crawler with every collaborator injected, including the clock - the seam a
     * determinism test uses to make {@link CrawledPage#fetchDuration()} reproducible.
     */
    public HttpCrawler(
            IHttpFetcher fetcher,
            IHtmlLinkExtractor linkExtractor,
            ICrawlScopePolicy scopePolicy,
            IWaitSleeper sleeper,
            IMonotonicClock clock) {
        this(fetcher, linkExtractor, scopePolicy, sleeper, clock, Optional.empty());
    }

    private HttpCrawler(
            IHttpFetcher fetcher,
            IHtmlLinkExtractor linkExtractor,
            ICrawlScopePolicy scopePolicy,
            IWaitSleeper sleeper,
            IMonotonicClock clock,
            Optional<INetworkPolicy> networkPolicy) {
        this.fetcher = Objects.requireNonNull(fetcher, "fetcher");
        this.linkExtractor = Objects.requireNonNull(linkExtractor, "linkExtractor");
        this.scopePolicy = Objects.requireNonNull(scopePolicy, "scopePolicy");
        this.sleeper = Objects.requireNonNull(sleeper, "sleeper");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.networkPolicy = Objects.requireNonNull(networkPolicy, "networkPolicy");
    }

    /**
     * Returns a new crawler, otherwise identical to this one, that evaluates {@code policy} against
     * every real HTTP request this crawler is about to send - the task's own seed/discovered URL
     * and every redirect hop alike - strictly before that request is sent. A denied URL is recorded
     * as a {@link io.webagent4j.crawler.api.CrawlFailureType#NETWORK_POLICY_DENIED} failure, is
     * never retried, and never counts against {@link CrawlRequest#maxPages()}'s fetch-identity
     * budget. This crawler instance is unaffected; {@code HttpCrawler} is otherwise stateless and
     * reusable, so the returned instance is independently reusable too.
     */
    public HttpCrawler withNetworkPolicy(INetworkPolicy policy) {
        return new HttpCrawler(
                fetcher,
                linkExtractor,
                scopePolicy,
                sleeper,
                clock,
                Optional.of(Objects.requireNonNull(policy, "policy")));
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

        /**
         * Discovery-level identity: URLs claimed by navigation/frontier discovery (never a redirect
         * hop).
         */
        private final InMemoryCrawlDeduplicator dedup = new InMemoryCrawlDeduplicator();

        /**
         * Fetch-level identity: normalized URLs for which a real HTTP request was actually claimed
         * - a task's own URL and every redirect hop, distinct from {@link #dedup} so a discovery
         * dedup never gets silently conflated with the separate "was this URL actually requested"
         * question a redirect chain raises.
         */
        private final Set<URI> fetchedIdentities = new LinkedHashSet<>();

        private final List<CrawledPage> pages = new ArrayList<>();
        private final List<CrawlFailure> failures = new ArrayList<>();
        private final List<DiscoveredLink> rejectedUrls = new ArrayList<>();
        private int discoveredUrls;
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
                            fetchedIdentities.size(),
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
            if (fetchedIdentities.size() >= request.maxPages()) {
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
            long startNanos = clock.nanoTime();
            FetchOutcome outcome = fetchWithRedirects(task);
            Duration fetchDuration = Duration.ofNanos(clock.nanoTime() - startNanos);
            totalBytes += outcome.bytesRead();
            redirectCount += outcome.chain().size();

            if (outcome.response().isEmpty()) {
                recordFailure(
                        task,
                        outcome.failureType(),
                        outcome.message(),
                        outcome.failedUrl(),
                        outcome.statusCode(),
                        outcome.cause(),
                        outcome.attempts(),
                        outcome.chain());
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
                        finalUrl,
                        Optional.of(response.statusCode()),
                        Optional.empty(),
                        outcome.attempts(),
                        outcome.chain());
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
                        finalUrl,
                        Optional.of(response.statusCode()),
                        Optional.of(malformed),
                        outcome.attempts(),
                        outcome.chain());
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
                DiscoveredLink rejected = rejectedLink(link, Optional.empty(), scopeDecision);
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
                                Optional.empty(),
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
                                Optional.of(normalizedCandidate),
                                CrawlDecision.reject(
                                        CrawlDecisionType.REJECT_DEPTH,
                                        "depth "
                                                + childDepth
                                                + " exceeds maxDepth "
                                                + request.maxDepth()));
                rejectedUrls.add(rejected);
                return rejected;
            }

            if (fetchedIdentities.size() >= request.maxPages()) {
                maxPagesHit = true;
                DiscoveredLink rejected =
                        rejectedLink(
                                link,
                                Optional.of(normalizedCandidate),
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
                                Optional.of(normalizedCandidate),
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
                    Optional.of(normalizedCandidate),
                    link.rawHref(),
                    link.anchorText(),
                    link.kind(),
                    true,
                    Optional.empty(),
                    link.documentOrder());
        }

        private DiscoveredLink rejectedLink(
                ExtractedLink link, Optional<URI> normalizedUrl, CrawlDecision decision) {
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
                URI failedUrl,
                Optional<Integer> statusCode,
                Optional<Throwable> cause,
                int attempts,
                List<RedirectHop> redirectChain) {
            failures.add(
                    new CrawlFailure(
                            task.url(),
                            failedUrl,
                            task.depth(),
                            type,
                            message,
                            statusCode,
                            cause,
                            attempts,
                            task.discoveredFrom(),
                            redirectChain));
            if (request.failFast() && isFatal(type)) {
                stopRequested = true;
            }
        }

        /**
         * Whether {@code type} represents a genuine, unexpected fetch failure that {@code failFast}
         * should abort the whole crawl for. {@link CrawlFailureType#CRAWL_LIMIT_REACHED} and {@link
         * CrawlFailureType#ALREADY_FETCHED} are ordinary, expected outcomes of the crawl's own
         * graph (a user-requested page budget, or two paths converging on the same URL) - never a
         * backend problem - so {@code failFast} never turns either into a {@link
         * CrawlTerminationReason#FATAL_ERROR}; the crawl keeps processing the rest of the frontier
         * instead.
         */
        private static boolean isFatal(CrawlFailureType type) {
            return type != CrawlFailureType.CRAWL_LIMIT_REACHED
                    && type != CrawlFailureType.ALREADY_FETCHED;
        }

        /**
         * Claims {@code normalizedUrl} against the crawl-wide fetch identity budget - the one gate
         * every real HTTP request passes through, whether it is a task's own URL or a redirect hop.
         */
        private FetchClaimOutcome claimFetchIdentity(URI normalizedUrl) {
            if (fetchedIdentities.contains(normalizedUrl)) {
                return FetchClaimOutcome.ALREADY_FETCHED;
            }
            if (fetchedIdentities.size() >= request.maxPages()) {
                maxPagesHit = true;
                return FetchClaimOutcome.LIMIT_REACHED;
            }
            fetchedIdentities.add(normalizedUrl);
            return FetchClaimOutcome.CLAIMED;
        }

        /**
         * Follows redirects for {@code task}'s URL, one hop at a time - every hop normalized,
         * scope-checked, and claimed against the fetch identity budget before it is ever requested.
         */
        private FetchOutcome fetchWithRedirects(CrawlTask task) {
            URI current = task.url();
            List<RedirectHop> chain = new ArrayList<>();
            Set<URI> visited = new LinkedHashSet<>();
            long bytesRead = 0;

            Optional<PolicyDenial> initialDenial = checkNetworkPolicy(current);
            if (initialDenial.isPresent()) {
                return FetchOutcome.failure(
                        initialDenial.get().type(),
                        initialDenial.get().message(),
                        current,
                        Optional.empty(),
                        Optional.empty(),
                        0,
                        bytesRead,
                        chain);
            }

            FetchClaimOutcome initialClaim = claimFetchIdentity(current);
            if (initialClaim == FetchClaimOutcome.LIMIT_REACHED) {
                return FetchOutcome.failure(
                        CrawlFailureType.CRAWL_LIMIT_REACHED,
                        "maxPages " + request.maxPages() + " already reached",
                        current,
                        Optional.empty(),
                        Optional.empty(),
                        0,
                        bytesRead,
                        chain);
            }
            if (initialClaim == FetchClaimOutcome.ALREADY_FETCHED) {
                return FetchOutcome.failure(
                        CrawlFailureType.ALREADY_FETCHED,
                        "already fetched earlier in this crawl, reached again via a different"
                                + " discovery path",
                        current,
                        Optional.empty(),
                        Optional.empty(),
                        0,
                        bytesRead,
                        chain);
            }
            visited.add(current);
            // A real HTTP request is actually about to be sent for this task - only now does its
            // depth count toward maxDepthReached. Redirect hops resolved below never change it: a
            // task's depth is fixed, regardless of how many hops its own request chain follows.
            maxDepthReached = Math.max(maxDepthReached, task.depth());

            while (true) {
                RetryOutcome retryOutcome = fetchWithRetries(current);
                bytesRead += retryOutcome.bytesRead();
                if (retryOutcome.result().isEmpty()) {
                    return FetchOutcome.failure(
                            retryOutcome.failureType(),
                            retryOutcome.message(),
                            current,
                            Optional.empty(),
                            retryOutcome.cause(),
                            retryOutcome.attempts(),
                            bytesRead,
                            chain);
                }
                HttpFetchResult response = retryOutcome.result().get();
                int status = response.statusCode();
                int attemptsMade = retryOutcome.attempts();

                if (HttpResponseClassifier.isRedirect(status)) {
                    if (chain.size() >= request.maxRedirects()) {
                        return FetchOutcome.failure(
                                CrawlFailureType.TOO_MANY_REDIRECTS,
                                "exceeded maxRedirects=" + request.maxRedirects(),
                                current,
                                Optional.of(status),
                                Optional.empty(),
                                attemptsMade,
                                bytesRead,
                                chain);
                    }
                    Optional<URI> locationRaw = redirectLocation(response, current);
                    if (locationRaw.isEmpty()) {
                        return FetchOutcome.failure(
                                CrawlFailureType.INVALID_REDIRECT,
                                "missing or invalid Location header",
                                current,
                                Optional.of(status),
                                Optional.empty(),
                                attemptsMade,
                                bytesRead,
                                chain);
                    }
                    URI target;
                    try {
                        target = normalizer.normalize(locationRaw.get());
                    } catch (IllegalArgumentException notNormalizable) {
                        return FetchOutcome.failure(
                                CrawlFailureType.INVALID_REDIRECT,
                                "redirect target could not be normalized: "
                                        + notNormalizable.getMessage(),
                                current,
                                Optional.of(status),
                                Optional.empty(),
                                attemptsMade,
                                bytesRead,
                                chain);
                    }
                    if (!visited.add(target)) {
                        return FetchOutcome.failure(
                                CrawlFailureType.REDIRECT_LOOP,
                                "redirect loop detected at " + target,
                                target,
                                Optional.of(status),
                                Optional.empty(),
                                attemptsMade,
                                bytesRead,
                                chain);
                    }
                    CrawlDecision decision = scopePolicy.evaluate(target, current, request);
                    if (!decision.allowed()) {
                        return FetchOutcome.failure(
                                CrawlFailureType.INVALID_REDIRECT,
                                "redirect target rejected by scope policy: " + decision.reason(),
                                target,
                                Optional.of(status),
                                Optional.empty(),
                                attemptsMade,
                                bytesRead,
                                chain);
                    }
                    Optional<PolicyDenial> hopDenial = checkNetworkPolicy(target);
                    if (hopDenial.isPresent()) {
                        // Zero, not attemptsMade: attemptsMade counts requests already sent for
                        // the referring URL that produced this redirect, but no real HTTP request
                        // was ever sent for "target" itself - exactly like CRAWL_LIMIT_REACHED and
                        // ALREADY_FETCHED just below, both handled the same way for the same
                        // reason.
                        return FetchOutcome.failure(
                                hopDenial.get().type(),
                                hopDenial.get().message(),
                                target,
                                Optional.of(status),
                                Optional.empty(),
                                0,
                                bytesRead,
                                chain);
                    }
                    FetchClaimOutcome hopClaim = claimFetchIdentity(target);
                    if (hopClaim == FetchClaimOutcome.LIMIT_REACHED) {
                        return FetchOutcome.failure(
                                CrawlFailureType.CRAWL_LIMIT_REACHED,
                                "maxPages "
                                        + request.maxPages()
                                        + " already reached; cannot fetch redirect target "
                                        + target,
                                target,
                                Optional.of(status),
                                Optional.empty(),
                                0,
                                bytesRead,
                                chain);
                    }
                    if (hopClaim == FetchClaimOutcome.ALREADY_FETCHED) {
                        return FetchOutcome.failure(
                                CrawlFailureType.ALREADY_FETCHED,
                                "redirect target "
                                        + target
                                        + " was already fetched earlier in"
                                        + " this crawl",
                                target,
                                Optional.of(status),
                                Optional.empty(),
                                0,
                                bytesRead,
                                chain);
                    }
                    chain.add(new RedirectHop(current, target, status));
                    current = target;
                    continue;
                }

                if (HttpResponseClassifier.isSuccess(status)) {
                    return FetchOutcome.success(response, current, bytesRead, chain, attemptsMade);
                }

                CrawlFailureType type;
                if (HttpResponseClassifier.isClientError(status)) {
                    type = CrawlFailureType.HTTP_CLIENT_ERROR;
                } else if (HttpResponseClassifier.isServerError(status)) {
                    type = CrawlFailureType.HTTP_SERVER_ERROR;
                } else {
                    type = CrawlFailureType.UNEXPECTED_HTTP_STATUS;
                }
                return FetchOutcome.failure(
                        type,
                        "HTTP status " + status,
                        current,
                        Optional.of(status),
                        Optional.empty(),
                        attemptsMade,
                        bytesRead,
                        chain);
            }
        }

        /**
         * Retries one hop's fetch per {@link CrawlRequest#retryPolicy()}, sleeping via {@link
         * IWaitSleeper}. The returned {@link RetryOutcome} always carries the real attempt count,
         * whether it ultimately succeeded or exhausted its retry budget.
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
                    return RetryOutcome.success(result, bytesRead, attempt);
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

        /**
         * Evaluates this crawler's configured network policy, if any, against {@code uri} - always
         * called strictly before that URI's real HTTP request is ever sent, never after. Returns
         * {@link Optional#empty()} when there is no configured policy or it allows the request; a
         * present {@link PolicyDenial} carries the exact {@link CrawlFailureType} and message the
         * caller should record instead of ever sending the request.
         */
        private Optional<PolicyDenial> checkNetworkPolicy(URI uri) {
            if (networkPolicy.isEmpty()) {
                return Optional.empty();
            }
            NetworkPolicyContext policyContext;
            try {
                NetworkDestination destination = NetworkDestination.of(uri);
                policyContext =
                        new NetworkPolicyContext(
                                NetworkRequestKind.HTTP_FETCH,
                                destination,
                                NetworkCheckPhase.PRE_REQUEST);
            } catch (RuntimeException malformed) {
                return Optional.of(
                        new PolicyDenial(
                                CrawlFailureType.NETWORK_POLICY_EVALUATION_FAILED,
                                "URL could not be evaluated against the network policy: "
                                        + malformed.getMessage()));
            }
            PolicyDecision decision;
            try {
                decision = networkPolicy.get().evaluate(policyContext);
            } catch (RuntimeException evaluationFailure) {
                return Optional.of(
                        new PolicyDenial(
                                CrawlFailureType.NETWORK_POLICY_EVALUATION_FAILED,
                                "network policy evaluation failed: "
                                        + evaluationFailure.getMessage()));
            }
            if (decision == null) {
                return Optional.of(
                        new PolicyDenial(
                                CrawlFailureType.NETWORK_POLICY_EVALUATION_FAILED,
                                "network policy returned no decision"));
            }
            if (decision.isDeny()) {
                return Optional.of(
                        new PolicyDenial(
                                CrawlFailureType.NETWORK_POLICY_DENIED,
                                "network destination denied by policy: "
                                        + decision.reason().code()));
            }
            return Optional.empty();
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

    /** A network-policy check's denial: the failure type and message to record for it. */
    private record PolicyDenial(CrawlFailureType type, String message) {}

    /** Outcome of claiming a normalized URL against the crawl-wide fetch identity budget. */
    private enum FetchClaimOutcome {
        /** First claim for this identity - the caller may proceed to fetch it. */
        CLAIMED,
        /** This identity was already claimed and fetched earlier in the same crawl. */
        ALREADY_FETCHED,
        /** {@link CrawlRequest#maxPages()} identities are already claimed; this one cannot be. */
        LIMIT_REACHED
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
            URI failedUrl,
            long bytesRead,
            List<RedirectHop> chain,
            CrawlFailureType failureType,
            String message,
            Optional<Integer> statusCode,
            Optional<Throwable> cause,
            int attempts) {

        static FetchOutcome success(
                HttpFetchResult response,
                URI finalUrl,
                long bytesRead,
                List<RedirectHop> chain,
                int attempts) {
            return new FetchOutcome(
                    Optional.of(response),
                    finalUrl,
                    null,
                    bytesRead,
                    List.copyOf(chain),
                    null,
                    "",
                    Optional.empty(),
                    Optional.empty(),
                    attempts);
        }

        static FetchOutcome failure(
                CrawlFailureType failureType,
                String message,
                URI failedUrl,
                Optional<Integer> statusCode,
                Optional<Throwable> cause,
                int attempts,
                long bytesRead,
                List<RedirectHop> chain) {
            return new FetchOutcome(
                    Optional.empty(),
                    null,
                    failedUrl,
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

        static RetryOutcome success(HttpFetchResult result, long bytesRead, int attempts) {
            return new RetryOutcome(
                    Optional.of(result), bytesRead, null, "", Optional.empty(), attempts);
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
