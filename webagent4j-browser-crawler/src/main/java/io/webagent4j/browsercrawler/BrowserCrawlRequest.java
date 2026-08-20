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
 * @param browser the already-launched browser this crawl navigates within. One isolated
 *     session/context per crawl is exactly what one {@code IBrowser} instance already is (cookies,
 *     storage, and authentication state are shared across every page the crawl opens on it) - see
 *     {@code docs/browser-crawler.md#session-model}. Never closed by the crawler unless {@link
 *     #closeBrowserOnCompletion()} is {@code true}.
 * @param seeds absolute {@code http}/{@code https} URLs to start from, depth {@code 0}
 * @param maxDepth the greatest depth a claimed navigation may have
 * @param maxPages the greatest number of navigations this crawl may claim - enforced by one
 *     synchronized gate shared by every worker, so it is exact under concurrency
 * @param sameHostOnly whether only the seeds' own hosts (not subdomains, unless {@link
 *     #includeSubdomains()}) are in scope
 * @param includeSubdomains whether subdomains of an in-scope host are also in scope
 * @param allowedHosts additional hosts considered in scope, beyond the seeds' own hosts
 * @param navigationTimeout the greatest time one navigation attempt (including reaching stability)
 *     may take before {@link BrowserCrawlFailureType#NAVIGATION_TIMEOUT}
 * @param stabilityWindow how long the page's DOM must report the same stability fingerprint before
 *     a page is considered stable - see {@code docs/browser-crawler.md#stability}
 * @param maxConcurrency the greatest number of pages navigated at once; bounds the number of
 *     browser pages/tabs the crawler creates
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
        Objects.requireNonNull(stabilityWindow, "stabilityWindow");
        if (stabilityWindow.isNegative() || stabilityWindow.isZero()) {
            throw new IllegalArgumentException("stabilityWindow must be positive");
        }
        if (maxConcurrency < 1) {
            throw new IllegalArgumentException(
                    "maxConcurrency must be >= 1, was " + maxConcurrency);
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
