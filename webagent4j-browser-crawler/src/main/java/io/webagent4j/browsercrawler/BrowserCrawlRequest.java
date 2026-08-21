package io.webagent4j.browsercrawler;

import io.webagent4j.browser.IBrowser;
import io.webagent4j.crawler.api.QueryParameterPolicy;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Immutable, fully validated configuration for one {@link
 * IBrowserCrawler#crawl(BrowserCrawlRequest)} call.
 *
 * <p>Every invalid configuration fails at {@link Builder#build()}, never partway through a crawl.
 * BFS is the only traversal strategy this phase implements - there is no {@code traversalStrategy}
 * field to expose, unlike {@code CrawlRequest}, since a field accepting only one value adds surface
 * without adding a real choice (see {@code docs/browser-crawler.md#public-api-minimization}).
 *
 * @param browser the already-launched browser this crawl runs within - the caller-supplied {@code
 *     IBrowser} instance IS the crawl's session boundary, not something {@code BrowserCrawler}
 *     creates or isolates itself. Cookies, storage, and authentication state already reachable
 *     through it are shared across every page this crawl opens, exactly as they would be for any
 *     other caller of the same {@code IBrowser} - if the caller reuses the same instance across
 *     multiple crawls (or alongside other automation on it), those crawls intentionally share that
 *     session state; isolating one crawl's session from another is the caller's responsibility. See
 *     {@code docs/browser-crawler.md#session-model}. The crawler owns only the {@code IPage} it
 *     creates and always closes it; {@code browser} itself is never closed by the crawler unless
 *     {@link #closeBrowserOnCompletion()} is {@code true}.
 * @param seeds absolute {@code http}/{@code https} URLs to start from, depth {@code 0}
 * @param maxDepth the greatest depth a claimed navigation may have
 * @param maxPages the greatest number of navigations this crawl may claim - enforced exactly by
 *     {@code ClaimGate}, which stays defensively synchronized even though, with {@link
 *     #maxConcurrency()} pinned to {@code 1}, only one thread ever calls it
 * @param sameHostOnly whether only the seeds' own hosts (not subdomains, unless {@link
 *     #includeSubdomains()}) are in scope
 * @param includeSubdomains whether subdomains of an in-scope host are also in scope
 * @param allowedHosts additional hosts considered in scope, beyond the seeds' own hosts
 * @param navigationTimeout the authoritative total budget for one navigation attempt - covering
 *     both the {@code navigate()} call itself (via {@link
 *     io.webagent4j.browser.IPage#navigate(String, java.time.Duration)}, not a backend's own
 *     default) and the subsequent stability wait, from a single monotonic deadline. Exceeded during
 *     navigation itself: {@link BrowserCrawlFailureType#NAVIGATION_TIMEOUT}; exceeded while waiting
 *     for stability: {@link BrowserCrawlFailureType#PAGE_STABILITY_TIMEOUT}
 * @param stabilityWindow how long the page's DOM must report the same stability fingerprint before
 *     a page is considered stable - see {@code docs/browser-crawler.md#stability}
 * @param maxConcurrency must currently be exactly {@code 1}. Neither {@link IBrowser} nor its pages
 *     are documented as thread-safe, and one caller-supplied {@code IBrowser} instance is the crawl
 *     session (cookies/storage/auth), so this engine navigates on a single execution lane - see
 *     {@code docs/browser-crawler.md#concurrency-model}. The field is kept, rather than removed, so
 *     a future phase that can honestly offer more than one lane (for example, one independent
 *     {@code IBrowser} per worker with an explicit session-sharing story) does not need a breaking
 *     API change to do it.
 * @param frameCrawlPolicy which frames to discover links from, in addition to the top-level
 *     document - see {@link FrameCrawlPolicy}
 * @param queryParameterPolicy how query parameters are normalized for the deduplication identity -
 *     reused unchanged from {@code webagent4j-crawler-api}
 * @param includeUrlPatterns if non-empty, a discovered URL must match at least one pattern to be in
 *     scope
 * @param excludeUrlPatterns a discovered URL matching any pattern is out of scope, checked after
 *     {@link #includeUrlPatterns()}
 * @param failFast whether a fatal page failure stops the crawl instead of being recorded and
 *     continuing
 * @param closeBrowserOnCompletion whether the crawler closes {@link #browser()} when the crawl ends
 *     (success, failure, or cancellation) - default {@code false}, respecting caller ownership
 * @param cancellationToken the token this crawl observes; {@link CancellationToken#cancel()} may be
 *     called from any thread
 */
public record BrowserCrawlRequest(
        IBrowser browser,
        List<URI> seeds,
        int maxDepth,
        int maxPages,
        boolean sameHostOnly,
        boolean includeSubdomains,
        Set<String> allowedHosts,
        Duration navigationTimeout,
        Duration stabilityWindow,
        int maxConcurrency,
        FrameCrawlPolicy frameCrawlPolicy,
        QueryParameterPolicy queryParameterPolicy,
        List<Pattern> includeUrlPatterns,
        List<Pattern> excludeUrlPatterns,
        boolean failFast,
        boolean closeBrowserOnCompletion,
        CancellationToken cancellationToken) {

    /** Validates every field; throws {@link IllegalArgumentException} for the first violation. */
    public BrowserCrawlRequest {
        Objects.requireNonNull(browser, "browser");
        Objects.requireNonNull(seeds, "seeds");
        if (seeds.isEmpty()) {
            throw new IllegalArgumentException("at least one seed is required");
        }
        seeds.forEach(BrowserCrawlRequest::requireAbsoluteHttpSeed);
        seeds = List.copyOf(seeds);
        if (maxDepth < 0) {
            throw new IllegalArgumentException("maxDepth must be >= 0, was " + maxDepth);
        }
        if (maxPages < 1) {
            throw new IllegalArgumentException("maxPages must be >= 1, was " + maxPages);
        }
        allowedHosts =
                Set.copyOf(
                        Objects.requireNonNull(allowedHosts, "allowedHosts").stream()
                                .map(host -> host.toLowerCase(Locale.ROOT))
                                .toList());
        Objects.requireNonNull(navigationTimeout, "navigationTimeout");
        if (navigationTimeout.isNegative() || navigationTimeout.isZero()) {
            throw new IllegalArgumentException("navigationTimeout must be positive");
        }
        if (navigationTimeout.toMillis() < 1) {
            // Both navigate() and waitForCondition() ultimately resolve to a millisecond-valued
            // backend timeout option (see IPage#requirePositiveMillisTimeout) - a positive but
            // sub-millisecond navigationTimeout can never be honestly honored at that resolution,
            // so it is rejected here rather than silently floored to a bound the caller never asked
            // for.
            throw new IllegalArgumentException(
                    "navigationTimeout must be at least 1 millisecond, was " + navigationTimeout);
        }
        Objects.requireNonNull(stabilityWindow, "stabilityWindow");
        if (stabilityWindow.isNegative() || stabilityWindow.isZero()) {
            throw new IllegalArgumentException("stabilityWindow must be positive");
        }
        if (stabilityWindow.toMillis() < 1) {
            throw new IllegalArgumentException(
                    "stabilityWindow must be at least 1 millisecond, was " + stabilityWindow);
        }
        if (stabilityWindow.compareTo(navigationTimeout) > 0) {
            // navigation and stability share one monotonic budget (navigationTimeout) - a
            // stabilityWindow longer than that whole budget could never be satisfied even if
            // navigation itself took zero time, so this configuration is rejected here rather than
            // discovered mid-crawl as a page that can structurally never succeed.
            throw new IllegalArgumentException(
                    "stabilityWindow ("
                            + stabilityWindow
                            + ") must not exceed navigationTimeout ("
                            + navigationTimeout
                            + ") - navigation and stability share one budget, so a longer stability"
                            + " window could never be satisfied");
        }
        if (maxConcurrency != 1) {
            throw new IllegalArgumentException(
                    "maxConcurrency must be exactly 1, was "
                            + maxConcurrency
                            + " - IBrowser/IPage are not documented as thread-safe, and one"
                            + " IBrowser instance is the crawl session, so this engine navigates"
                            + " on a single execution lane (see"
                            + " docs/browser-crawler.md#concurrency-model)");
        }
        Objects.requireNonNull(frameCrawlPolicy, "frameCrawlPolicy");
        if (frameCrawlPolicy != FrameCrawlPolicy.TOP_LEVEL_ONLY) {
            throw new IllegalArgumentException(
                    frameCrawlPolicy
                            + " is not yet implemented in this phase - only TOP_LEVEL_ONLY is"
                            + " supported (see FrameCrawlPolicy)");
        }
        Objects.requireNonNull(queryParameterPolicy, "queryParameterPolicy");
        includeUrlPatterns =
                List.copyOf(Objects.requireNonNull(includeUrlPatterns, "includeUrlPatterns"));
        excludeUrlPatterns =
                List.copyOf(Objects.requireNonNull(excludeUrlPatterns, "excludeUrlPatterns"));
        Objects.requireNonNull(cancellationToken, "cancellationToken");
    }

    private static void requireAbsoluteHttpSeed(URI seed) {
        Objects.requireNonNull(seed, "seed");
        if (!seed.isAbsolute() || seed.getHost() == null) {
            throw new IllegalArgumentException("seed must be an absolute URL with a host: " + seed);
        }
        String scheme = seed.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw new IllegalArgumentException(
                    "unsupported seed scheme '"
                            + scheme
                            + "' - the browser crawler only navigates http/https URLs: "
                            + seed);
        }
    }

    /** Returns a new builder with the same conservative defaults as {@code CrawlRequest}'s. */
    public static Builder builder(IBrowser browser) {
        return new Builder(browser);
    }

    /** Mutable builder for {@link BrowserCrawlRequest}. Every setter returns {@code this}. */
    public static final class Builder {

        private final IBrowser browser;
        private final List<URI> seeds = new ArrayList<>();
        private int maxDepth = 3;
        private int maxPages = 50;
        private boolean sameHostOnly = true;
        private boolean includeSubdomains = false;
        private final Set<String> allowedHosts = new LinkedHashSet<>();
        private Duration navigationTimeout = Duration.ofSeconds(15);
        private Duration stabilityWindow = Duration.ofMillis(500);
        private int maxConcurrency = 1;
        private FrameCrawlPolicy frameCrawlPolicy = FrameCrawlPolicy.TOP_LEVEL_ONLY;
        private QueryParameterPolicy queryParameterPolicy = QueryParameterPolicy.keepAll();
        private final List<Pattern> includeUrlPatterns = new ArrayList<>();
        private final List<Pattern> excludeUrlPatterns = new ArrayList<>();
        private boolean failFast = false;
        private boolean closeBrowserOnCompletion = false;
        private CancellationToken cancellationToken = CancellationToken.create();

        private Builder(IBrowser browser) {
            this.browser = browser;
        }

        /** Adds one seed URL, parsed with {@link URI#create(String)}. */
        public Builder seed(String uri) {
            return seed(URI.create(uri));
        }

        /** Adds one seed URL. */
        public Builder seed(URI uri) {
            seeds.add(uri);
            return this;
        }

        public Builder maxDepth(int value) {
            maxDepth = value;
            return this;
        }

        public Builder maxPages(int value) {
            maxPages = value;
            return this;
        }

        public Builder sameHostOnly(boolean value) {
            sameHostOnly = value;
            return this;
        }

        public Builder includeSubdomains(boolean value) {
            includeSubdomains = value;
            return this;
        }

        public Builder allowedHost(String host) {
            allowedHosts.add(host);
            return this;
        }

        public Builder navigationTimeout(Duration value) {
            navigationTimeout = value;
            return this;
        }

        public Builder stabilityWindow(Duration value) {
            stabilityWindow = value;
            return this;
        }

        public Builder maxConcurrency(int value) {
            maxConcurrency = value;
            return this;
        }

        public Builder frameCrawlPolicy(FrameCrawlPolicy value) {
            frameCrawlPolicy = value;
            return this;
        }

        public Builder queryParameterPolicy(QueryParameterPolicy value) {
            queryParameterPolicy = value;
            return this;
        }

        public Builder includeUrlPattern(String regex) {
            includeUrlPatterns.add(Pattern.compile(regex));
            return this;
        }

        public Builder excludeUrlPattern(String regex) {
            excludeUrlPatterns.add(Pattern.compile(regex));
            return this;
        }

        public Builder failFast(boolean value) {
            failFast = value;
            return this;
        }

        public Builder closeBrowserOnCompletion(boolean value) {
            closeBrowserOnCompletion = value;
            return this;
        }

        public Builder cancellationToken(CancellationToken value) {
            cancellationToken = value;
            return this;
        }

        /** Builds and validates the request. */
        public BrowserCrawlRequest build() {
            return new BrowserCrawlRequest(
                    browser,
                    seeds,
                    maxDepth,
                    maxPages,
                    sameHostOnly,
                    includeSubdomains,
                    allowedHosts,
                    navigationTimeout,
                    stabilityWindow,
                    maxConcurrency,
                    frameCrawlPolicy,
                    queryParameterPolicy,
                    includeUrlPatterns,
                    excludeUrlPatterns,
                    failFast,
                    closeBrowserOnCompletion,
                    cancellationToken);
        }
    }
}
