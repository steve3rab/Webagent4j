package io.webagent4j.crawler;

import static org.assertj.core.api.Assertions.assertThat;

import io.webagent4j.crawler.api.CrawlFailure;
import io.webagent4j.crawler.api.CrawlFailureType;
import io.webagent4j.crawler.api.CrawlRequest;
import io.webagent4j.crawler.api.CrawlResult;
import io.webagent4j.policy.PolicyDecision;
import io.webagent4j.policy.network.INetworkAddressAuthority;
import io.webagent4j.policy.network.INetworkPolicy;
import io.webagent4j.policy.network.NetworkDestination;
import io.webagent4j.policy.network.VerifiedNetworkAddresses;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Proves how {@link HttpCrawler} wires transport pinning into every real HTTP attempt: a network
 * policy that also implements {@link INetworkAddressAuthority} routes each request through {@link
 * IHttpFetcher}'s pinning-aware overload with its authorized address set; every other case - no
 * policy, or a policy that never implements the capability - keeps calling the exact same one
 * -argument overload as before this feature existed, so an ungoverned crawl's behavior (and cost)
 * is completely unchanged. A policy that claims the capability but cannot re-confirm a destination
 * it just allowed is treated as a fresh denial, never a silent fall back to an unpinned request.
 */
class HttpCrawlerNetworkPinningTest {

    @Test
    void ungovernedCrawlNeverCallsThePinningAwareOverload() {
        URI seed = URI.create("https://ungoverned.example.test/");
        RecordingFetcher fetcher = new RecordingFetcher();
        fetcher.respondHtml(seed, "<html><body>ok</body></html>");

        CrawlResult result =
                crawler(fetcher).crawl(CrawlRequest.builder().seed(seed).maxPages(5).build());

        assertThat(result.pages()).hasSize(1);
        assertThat(fetcher.oneArgCalls()).containsExactly(seed);
        assertThat(fetcher.pinnedCalls()).isEmpty();
    }

    @Test
    void aPlainCustomPolicyThatOffersNoPinningCapabilityFallsBackToTheUnpinnedOverload() {
        URI seed = URI.create("https://allowed.example.test/");
        RecordingFetcher fetcher = new RecordingFetcher();
        fetcher.respondHtml(seed, "<html><body>ok</body></html>");
        INetworkPolicy plainAllow = context -> PolicyDecision.allow("test.network.allowed");

        CrawlResult result =
                crawler(fetcher)
                        .withNetworkPolicy(plainAllow)
                        .crawl(CrawlRequest.builder().seed(seed).maxPages(5).build());

        assertThat(result.pages()).hasSize(1);
        assertThat(fetcher.oneArgCalls()).containsExactly(seed);
        assertThat(fetcher.pinnedCalls()).isEmpty();
    }

    @Test
    void aPinningCapablePolicyRoutesTheRequestThroughTheTwoArgOverloadWithItsAuthorizedAddresses()
            throws UnknownHostException {
        URI seed = URI.create("https://pinned.example.test/");
        RecordingFetcher fetcher = new RecordingFetcher();
        fetcher.respondHtml(seed, "<html><body>ok</body></html>");
        VerifiedNetworkAddresses verified =
                new VerifiedNetworkAddresses(
                        "pinned.example.test",
                        443,
                        List.of(InetAddress.getByName("93.184.216.34")));
        INetworkPolicy pinningPolicy = new FakePinningPolicy(true, Optional.of(verified));

        CrawlResult result =
                crawler(fetcher)
                        .withNetworkPolicy(pinningPolicy)
                        .crawl(CrawlRequest.builder().seed(seed).maxPages(5).build());

        assertThat(result.pages()).hasSize(1);
        assertThat(fetcher.oneArgCalls()).isEmpty();
        assertThat(fetcher.pinnedCalls()).containsExactly(Optional.of(verified));
    }

    @Test
    void aPolicyThatAllowsButCannotReconfirmPinningIsDeniedRatherThanFallingBackToUnpinned() {
        URI seed = URI.create("https://claims-pinning.example.test/");
        RecordingFetcher fetcher = new RecordingFetcher();
        INetworkPolicy unreliablePinningPolicy = new FakePinningPolicy(true, Optional.empty());

        CrawlResult result =
                crawler(fetcher)
                        .withNetworkPolicy(unreliablePinningPolicy)
                        .crawl(CrawlRequest.builder().seed(seed).maxPages(5).build());

        assertThat(result.pages()).isEmpty();
        assertThat(fetcher.oneArgCalls()).isEmpty();
        assertThat(fetcher.pinnedCalls()).isEmpty();
        assertThat(result.failures()).hasSize(1);
        CrawlFailure failure = result.failures().get(0);
        assertThat(failure.type()).isEqualTo(CrawlFailureType.NETWORK_POLICY_DENIED);
    }

    @Test
    void np001APinningCapablePolicyIsAuthorizedThroughOneCallNeverASeparatePrecedingEvaluate()
            throws UnknownHostException {
        // NP-001: evaluate() and authorizeConnection() would otherwise each perform their own
        // independent DNS resolution, so an ALLOW decision could rest on one resolver answer
        // while the pinned address set actually used came from a second, later resolution - a
        // narrow DNS-rebinding window between the two. For one logical attempt, both the
        // authorization decision and the pinned addresses must derive from exactly one call.
        URI seed = URI.create("https://single-resolution.example.test/");
        RecordingFetcher fetcher = new RecordingFetcher();
        fetcher.respondHtml(seed, "<html><body>ok</body></html>");
        VerifiedNetworkAddresses verified =
                new VerifiedNetworkAddresses(
                        "single-resolution.example.test",
                        443,
                        List.of(InetAddress.getByName("93.184.216.34")));
        CountingPinningPolicy policy = new CountingPinningPolicy(Optional.of(verified));

        CrawlResult result =
                crawler(fetcher)
                        .withNetworkPolicy(policy)
                        .crawl(CrawlRequest.builder().seed(seed).maxPages(5).build());

        assertThat(result.pages()).hasSize(1);
        assertThat(policy.evaluateCalls()).isZero();
        assertThat(policy.authorizeConnectionCalls()).isEqualTo(1);
    }

    @Test
    void everyRetryAttemptIsAuthorizedAndPinnedAfresh() throws UnknownHostException {
        URI seed = URI.create("https://retried.example.test/");
        RecordingFetcher fetcher = new RecordingFetcher();
        fetcher.respond(
                seed,
                new HttpFetchResult(seed, 503, Map.of(), new byte[0], "text/html", Duration.ZERO));
        fetcher.respondHtml(seed, "<html><body>ok</body></html>");
        VerifiedNetworkAddresses first =
                new VerifiedNetworkAddresses(
                        "retried.example.test",
                        443,
                        List.of(InetAddress.getByName("93.184.216.34")));
        VerifiedNetworkAddresses second =
                new VerifiedNetworkAddresses(
                        "retried.example.test",
                        443,
                        List.of(InetAddress.getByName("93.184.216.90")));
        AtomicInteger authorizationCount = new AtomicInteger();
        INetworkPolicy alternatingPinningPolicy =
                new INetworkPolicy() {
                    @Override
                    public PolicyDecision evaluate(
                            io.webagent4j.policy.network.NetworkPolicyContext context) {
                        return PolicyDecision.allow("test.network.allowed");
                    }
                };
        INetworkAddressAuthority alternatingAuthority =
                destination ->
                        Optional.of(authorizationCount.getAndIncrement() == 0 ? first : second);
        INetworkPolicy combined =
                new CombinedPolicy(alternatingPinningPolicy, alternatingAuthority);

        CrawlResult result =
                crawler(fetcher)
                        .withNetworkPolicy(combined)
                        .crawl(CrawlRequest.builder().seed(seed).maxPages(5).build());

        assertThat(result.pages()).hasSize(1);
        assertThat(fetcher.pinnedCalls()).containsExactly(Optional.of(first), Optional.of(second));
    }

    private static HttpCrawler crawler(IHttpFetcher fetcher) {
        return new HttpCrawler(
                fetcher, new FakeLinkExtractor(), new HostScopePolicy(), duration -> {});
    }

    /** A policy whose {@code evaluate} outcome and pinning offer are independently configurable. */
    private static final class FakePinningPolicy
            implements INetworkPolicy, INetworkAddressAuthority {
        private final boolean allow;
        private final Optional<VerifiedNetworkAddresses> pinnedAddresses;

        FakePinningPolicy(boolean allow, Optional<VerifiedNetworkAddresses> pinnedAddresses) {
            this.allow = allow;
            this.pinnedAddresses = pinnedAddresses;
        }

        @Override
        public PolicyDecision evaluate(io.webagent4j.policy.network.NetworkPolicyContext context) {
            return allow
                    ? PolicyDecision.allow("test.network.allowed")
                    : PolicyDecision.deny("test.network.denied");
        }

        @Override
        public Optional<VerifiedNetworkAddresses> authorizeConnection(
                NetworkDestination destination) {
            return pinnedAddresses;
        }
    }

    /** Counts calls to each method separately, so a test can prove one of them was never used. */
    private static final class CountingPinningPolicy
            implements INetworkPolicy, INetworkAddressAuthority {
        private final Optional<VerifiedNetworkAddresses> pinnedAddresses;
        private final AtomicInteger evaluateCalls = new AtomicInteger();
        private final AtomicInteger authorizeConnectionCalls = new AtomicInteger();

        CountingPinningPolicy(Optional<VerifiedNetworkAddresses> pinnedAddresses) {
            this.pinnedAddresses = pinnedAddresses;
        }

        @Override
        public PolicyDecision evaluate(io.webagent4j.policy.network.NetworkPolicyContext context) {
            evaluateCalls.incrementAndGet();
            return PolicyDecision.allow("test.network.allowed");
        }

        @Override
        public Optional<VerifiedNetworkAddresses> authorizeConnection(
                NetworkDestination destination) {
            authorizeConnectionCalls.incrementAndGet();
            return pinnedAddresses;
        }

        int evaluateCalls() {
            return evaluateCalls.get();
        }

        int authorizeConnectionCalls() {
            return authorizeConnectionCalls.get();
        }
    }

    /** Combines a separately-configurable {@code evaluate} and {@code authorizeConnection}. */
    private static final class CombinedPolicy implements INetworkPolicy, INetworkAddressAuthority {
        private final INetworkPolicy evaluator;
        private final INetworkAddressAuthority authority;

        CombinedPolicy(INetworkPolicy evaluator, INetworkAddressAuthority authority) {
            this.evaluator = evaluator;
            this.authority = authority;
        }

        @Override
        public PolicyDecision evaluate(io.webagent4j.policy.network.NetworkPolicyContext context) {
            return evaluator.evaluate(context);
        }

        @Override
        public Optional<VerifiedNetworkAddresses> authorizeConnection(
                NetworkDestination destination) {
            return authority.authorizeConnection(destination);
        }
    }

    private static final class FakeLinkExtractor implements IHtmlLinkExtractor {
        @Override
        public LinkExtractionResult extract(String html, URI baseUri) {
            return new LinkExtractionResult(List.of(), Optional.empty(), Optional.empty());
        }
    }

    /**
     * Records which {@link IHttpFetcher} overload each request used, never touching the network.
     */
    private static final class RecordingFetcher implements IHttpFetcher {
        private final Map<URI, Deque<HttpFetchResult>> queues = new HashMap<>();
        private final List<URI> oneArgCalls = new ArrayList<>();
        private final List<Optional<VerifiedNetworkAddresses>> pinnedCalls = new ArrayList<>();

        void respondHtml(URI uri, String html) {
            enqueue(
                    uri,
                    new HttpFetchResult(
                            uri,
                            200,
                            Map.of(),
                            html.getBytes(StandardCharsets.UTF_8),
                            "text/html",
                            Duration.ZERO));
        }

        void respond(URI uri, HttpFetchResult result) {
            enqueue(uri, result);
        }

        private void enqueue(URI uri, HttpFetchResult result) {
            queues.computeIfAbsent(uri, key -> new ArrayDeque<>()).add(result);
        }

        @Override
        public HttpFetchResult fetch(HttpFetchRequest request) throws IOException {
            oneArgCalls.add(request.uri());
            return poll(request.uri());
        }

        @Override
        public HttpFetchResult fetch(
                HttpFetchRequest request, Optional<VerifiedNetworkAddresses> pinnedAddresses)
                throws IOException {
            pinnedCalls.add(pinnedAddresses);
            return poll(request.uri());
        }

        private HttpFetchResult poll(URI uri) {
            Deque<HttpFetchResult> queue = queues.get(uri);
            if (queue == null || queue.isEmpty()) {
                throw new AssertionError("test bug: no fake response queued for " + uri);
            }
            return queue.size() > 1 ? queue.poll() : queue.peek();
        }

        List<URI> oneArgCalls() {
            return oneArgCalls;
        }

        List<Optional<VerifiedNetworkAddresses>> pinnedCalls() {
            return pinnedCalls;
        }
    }
}
