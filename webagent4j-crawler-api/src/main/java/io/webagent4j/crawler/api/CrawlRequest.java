package io.webagent4j.crawler.api;

import io.webagent4j.common.RetryPolicy;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Immutable, fully-validated configuration for one {@link ICrawler#crawl(CrawlRequest)} call.
 *
 * <p>Every value is validated at construction time - never discovered mid-crawl. Build one with
 * {@link #builder()}.
 *
 * @param seeds the crawl's starting URLs, in insertion order; each is its own allowed-host root
 *     (see {@link #sameHostOnly()})
 * @param maxDepth the greatest depth a fetched URL may have; a seed is depth 0
 * @param maxPages the greatest number of unique URLs this crawl may start a fetch attempt for
 * @param sameHostOnly whether a discovered link's host must exactly match one of the seeds' (or
 *     {@link #allowedHosts()}'s) hosts
 * @param includeSubdomains whether a discovered link's host may also be a true subdomain of an
 *     allowed host - never a bare {@code endsWith} suffix match, which would also accept a
 *     lookalike host such as {@code evil-example.com} for {@code example.com}
 * @param allowedHosts additional allowed host roots beyond the seeds themselves
 * @param allowedSchemes schemes a discovered link may use to enter the frontier
 * @param requestTimeout the per-request timeout for one fetch attempt
 * @param maxResponseBytes the greatest response body size this crawl will read
 * @param maxRedirects the greatest number of redirect hops followed for one fetch attempt
 * @param retryPolicy attempt count and backoff shared by every retryable fetch
 * @param retryableStatusCodes HTTP status codes eligible for retry
 * @param retryOnIoException whether a network/IO failure is eligible for retry
 * @param userAgent the {@code User-Agent} header sent with every request
 * @param defaultHeaders additional headers sent with every request
 * @param allowedContentTypes {@code Content-Type} values (without parameters) this crawl will parse
 *     as HTML
 * @param traversalStrategy the frontier's release order
 * @param queryParameterPolicy which query parameters survive URL normalization
 * @param includeUrlPatterns when non-empty, a candidate URL must match at least one to be allowed
 * @param excludeUrlPatterns a candidate URL matching any of these is rejected
 * @param failFast whether one page's terminal failure stops the whole crawl ({@link
 *     CrawlTerminationReason#FATAL_ERROR}) instead of being recorded and continuing
 */
public record CrawlRequest(
        List<URI> seeds,
        int maxDepth,
        int maxPages,
        boolean sameHostOnly,
        boolean includeSubdomains,
        Set<String> allowedHosts,
        Set<String> allowedSchemes,
        Duration requestTimeout,
        long maxResponseBytes,
        int maxRedirects,
        RetryPolicy retryPolicy,
        Set<Integer> retryableStatusCodes,
        boolean retryOnIoException,
        String userAgent,
        Map<String, String> defaultHeaders,
        Set<String> allowedContentTypes,
        TraversalStrategy traversalStrategy,
        QueryParameterPolicy queryParameterPolicy,
        List<Pattern> includeUrlPatterns,
        List<Pattern> excludeUrlPatterns,
        boolean failFast) {

    /** Default {@code User-Agent}, never a browser impersonation string. */
    public static final String DEFAULT_USER_AGENT = "WebAgent4J-Crawler/0.1";

    /** Validates every field and defensively copies every collection. */
    public CrawlRequest {
        Objects.requireNonNull(seeds, "seeds");
        if (seeds.isEmpty()) {
            throw new IllegalArgumentException("at least one seed is required");
        }
        seeds = List.copyOf(seeds);
        allowedSchemes = lowercased(allowedSchemes);
        if (allowedSchemes.isEmpty()) {
            throw new IllegalArgumentException("allowedSchemes cannot be empty");
        }
        for (URI seed : seeds) {
            requireAbsoluteHttpSeed(seed);
        }
        if (maxDepth < 0) {
            throw new IllegalArgumentException("maxDepth cannot be negative");
        }
        if (maxPages < 1) {
            throw new IllegalArgumentException("maxPages must be at least one");
        }
        allowedHosts = lowercased(allowedHosts);
        Objects.requireNonNull(requestTimeout, "requestTimeout");
        if (requestTimeout.isZero() || requestTimeout.isNegative()) {
            throw new IllegalArgumentException("requestTimeout must be positive");
        }
        if (maxResponseBytes <= 0) {
            throw new IllegalArgumentException("maxResponseBytes must be positive");
        }
        if (maxRedirects < 0) {
            throw new IllegalArgumentException("maxRedirects cannot be negative");
        }
        Objects.requireNonNull(retryPolicy, "retryPolicy");
        retryableStatusCodes =
                Set.copyOf(Objects.requireNonNull(retryableStatusCodes, "retryableStatusCodes"));
        Objects.requireNonNull(userAgent, "userAgent");
        if (userAgent.isBlank()) {
            throw new IllegalArgumentException("userAgent cannot be blank");
        }
        defaultHeaders = Map.copyOf(Objects.requireNonNull(defaultHeaders, "defaultHeaders"));
        allowedContentTypes = lowercased(allowedContentTypes);
        Objects.requireNonNull(traversalStrategy, "traversalStrategy");
        if (traversalStrategy == TraversalStrategy.DEPTH_FIRST) {
            throw new IllegalArgumentException(
                    "TraversalStrategy.DEPTH_FIRST is not yet implemented in this phase");
        }
        Objects.requireNonNull(queryParameterPolicy, "queryParameterPolicy");
        includeUrlPatterns =
                List.copyOf(Objects.requireNonNull(includeUrlPatterns, "includeUrlPatterns"));
        excludeUrlPatterns =
                List.copyOf(Objects.requireNonNull(excludeUrlPatterns, "excludeUrlPatterns"));
    }

    /** Starts a new builder with documented defaults; see each setter's Javadoc. */
    public static Builder builder() {
        return new Builder();
    }

    private static void requireAbsoluteHttpSeed(URI seed) {
        Objects.requireNonNull(seed, "seed");
        if (!seed.isAbsolute() || seed.getHost() == null) {
            throw new IllegalArgumentException("seed must be an absolute URL: " + seed);
        }
        String scheme = seed.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw new IllegalArgumentException("seed must be http(s): " + seed);
        }
    }

    private static Set<String> lowercased(Set<String> values) {
        Objects.requireNonNull(values, "values");
        Set<String> result = new LinkedHashSet<>();
        for (String value : values) {
            result.add(value.toLowerCase(Locale.ROOT));
        }
        return Set.copyOf(result);
    }

    /** Mutable, incrementally-built {@link CrawlRequest} factory. */
    public static final class Builder {

        private final List<URI> seeds = new ArrayList<>();
        private int maxDepth = 3;
        private int maxPages = 100;
        private boolean sameHostOnly = true;
        private boolean includeSubdomains;
        private final Set<String> allowedHosts = new LinkedHashSet<>();
        private Set<String> allowedSchemes = new LinkedHashSet<>(Set.of("http", "https"));
        private Duration requestTimeout = Duration.ofSeconds(10);
        private long maxResponseBytes = 5_000_000L;
        private int maxRedirects = 5;
        private RetryPolicy retryPolicy = RetryPolicy.defaults();
        private Set<Integer> retryableStatusCodes = Set.of(429, 500, 502, 503, 504);
        private boolean retryOnIoException = true;
        private String userAgent = DEFAULT_USER_AGENT;
        private final Map<String, String> defaultHeaders = new LinkedHashMap<>();
        private Set<String> allowedContentTypes =
                new LinkedHashSet<>(Set.of("text/html", "application/xhtml+xml"));
        private TraversalStrategy traversalStrategy = TraversalStrategy.BREADTH_FIRST;
        private QueryParameterPolicy queryParameterPolicy = QueryParameterPolicy.keepAll();
        private final List<Pattern> includeUrlPatterns = new ArrayList<>();
        private final List<Pattern> excludeUrlPatterns = new ArrayList<>();
        private boolean failFast;

        private Builder() {
            // use CrawlRequest.builder()
        }

        /** Adds one seed URL, parsed with {@link URI#create(String)}. */
        public Builder seed(String url) {
            return seed(URI.create(Objects.requireNonNull(url, "url")));
        }

        /** Adds one seed URL. */
        public Builder seed(URI url) {
            seeds.add(Objects.requireNonNull(url, "url"));
            return this;
        }

        /** Sets the greatest depth a fetched URL may have. Default: 3. */
        public Builder maxDepth(int maxDepth) {
            this.maxDepth = maxDepth;
            return this;
        }

        /** Sets the greatest number of unique URLs fetched. Default: 100. */
        public Builder maxPages(int maxPages) {
            this.maxPages = maxPages;
            return this;
        }

        /** Restricts discovered links to the seeds' exact hosts. Default: {@code true}. */
        public Builder sameHostOnly(boolean sameHostOnly) {
            this.sameHostOnly = sameHostOnly;
            return this;
        }

        /**
         * Allows discovered links on true subdomains of an allowed host. Default: {@code false}.
         */
        public Builder includeSubdomains(boolean includeSubdomains) {
            this.includeSubdomains = includeSubdomains;
            return this;
        }

        /** Adds one host, beyond the seeds, allowed to be followed. */
        public Builder allowedHost(String host) {
            allowedHosts.add(Objects.requireNonNull(host, "host").toLowerCase(Locale.ROOT));
            return this;
        }

        /** Replaces the allowed schemes. Default: {@code http}, {@code https}. */
        public Builder allowedSchemes(String... schemes) {
            this.allowedSchemes = new LinkedHashSet<>(List.of(schemes));
            return this;
        }

        /** Sets the per-request timeout. Default: 10 seconds. */
        public Builder timeout(Duration timeout) {
            this.requestTimeout = Objects.requireNonNull(timeout, "timeout");
            return this;
        }

        /** Sets the greatest response body size read. Default: 5,000,000 bytes. */
        public Builder maxResponseBytes(long maxResponseBytes) {
            this.maxResponseBytes = maxResponseBytes;
            return this;
        }

        /** Sets the greatest number of redirect hops followed per fetch attempt. Default: 5. */
        public Builder maxRedirects(int maxRedirects) {
            this.maxRedirects = maxRedirects;
            return this;
        }

        /** Sets the retry attempt count and backoff. Default: {@link RetryPolicy#defaults()}. */
        public Builder retryPolicy(RetryPolicy retryPolicy) {
            this.retryPolicy = Objects.requireNonNull(retryPolicy, "retryPolicy");
            return this;
        }

        /** Replaces the retryable HTTP status codes. Default: 429, 500, 502, 503, 504. */
        public Builder retryableStatusCodes(int... statusCodes) {
            Set<Integer> codes = new LinkedHashSet<>();
            for (int code : statusCodes) {
                codes.add(code);
            }
            this.retryableStatusCodes = codes;
            return this;
        }

        /** Sets whether a network/IO failure is retryable. Default: {@code true}. */
        public Builder retryOnIoException(boolean retryOnIoException) {
            this.retryOnIoException = retryOnIoException;
            return this;
        }

        /** Sets the {@code User-Agent} header. Default: {@link #DEFAULT_USER_AGENT}. */
        public Builder userAgent(String userAgent) {
            this.userAgent = Objects.requireNonNull(userAgent, "userAgent");
            return this;
        }

        /**
         * Adds one default header sent with every request. Never add {@code Authorization} or
         * {@code Cookie} here unless the caller genuinely intends every request to carry it -
         * neither is added by default.
         */
        public Builder defaultHeader(String name, String value) {
            defaultHeaders.put(
                    Objects.requireNonNull(name, "name"), Objects.requireNonNull(value, "value"));
            return this;
        }

        /**
         * Replaces the {@code Content-Type} values parsed as HTML. Default: {@code text/html},
         * {@code application/xhtml+xml}.
         */
        public Builder allowedContentTypes(String... contentTypes) {
            this.allowedContentTypes = new LinkedHashSet<>(List.of(contentTypes));
            return this;
        }

        /** Sets the frontier's release order. Default: {@link TraversalStrategy#BREADTH_FIRST}. */
        public Builder traversalStrategy(TraversalStrategy traversalStrategy) {
            this.traversalStrategy = Objects.requireNonNull(traversalStrategy, "traversalStrategy");
            return this;
        }

        /** Sets the query-parameter policy. Default: {@link QueryParameterPolicy#keepAll()}. */
        public Builder queryParameterPolicy(QueryParameterPolicy queryParameterPolicy) {
            this.queryParameterPolicy =
                    Objects.requireNonNull(queryParameterPolicy, "queryParameterPolicy");
            return this;
        }

        /** Adds one regex a candidate URL must match at least one of to be allowed. */
        public Builder includeUrlPattern(String regex) {
            includeUrlPatterns.add(Pattern.compile(Objects.requireNonNull(regex, "regex")));
            return this;
        }

        /** Adds one regex that rejects any matching candidate URL. */
        public Builder excludeUrlPattern(String regex) {
            excludeUrlPatterns.add(Pattern.compile(Objects.requireNonNull(regex, "regex")));
            return this;
        }

        /**
         * Sets whether one page's terminal failure stops the whole crawl. Default: {@code false} -
         * a failed page is recorded and the crawl continues.
         */
        public Builder failFast(boolean failFast) {
            this.failFast = failFast;
            return this;
        }

        /** Builds and validates the {@link CrawlRequest}. */
        public CrawlRequest build() {
            return new CrawlRequest(
                    seeds,
                    maxDepth,
                    maxPages,
                    sameHostOnly,
                    includeSubdomains,
                    allowedHosts,
                    allowedSchemes,
                    requestTimeout,
                    maxResponseBytes,
                    maxRedirects,
                    retryPolicy,
                    retryableStatusCodes,
                    retryOnIoException,
                    userAgent,
                    defaultHeaders,
                    allowedContentTypes,
                    traversalStrategy,
                    queryParameterPolicy,
                    includeUrlPatterns,
                    excludeUrlPatterns,
                    failFast);
        }
    }
}
