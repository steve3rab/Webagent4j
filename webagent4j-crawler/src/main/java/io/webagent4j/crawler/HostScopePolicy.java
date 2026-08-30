package io.webagent4j.crawler;

import io.webagent4j.crawler.api.CrawlDecision;
import io.webagent4j.crawler.api.CrawlDecisionType;
import io.webagent4j.crawler.api.CrawlRequest;
import io.webagent4j.crawler.api.ICrawlScopePolicy;
import java.net.URI;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Default {@link ICrawlScopePolicy}: checks scheme, then host/domain scope, then URL filters, in
 * that order - the first failing check determines the rejection reason.
 *
 * <p>Domain scope never uses a bare {@code String#endsWith} host suffix check, which would also
 * accept a lookalike host such as {@code evil-example.com} for the allowed host {@code
 * example.com}: a subdomain must be preceded by a literal {@code '.'} boundary.
 *
 * <p><b>CRAWL-REGEX-001 - URL filter evaluation is bounded, not fully contained:</b> {@link
 * CrawlRequest#includeUrlPatterns()}/{@link CrawlRequest#excludeUrlPatterns()} are caller-supplied
 * {@code java.util.regex.Pattern}s evaluated against attacker-influenced discovered URLs (this is
 * not remote regex injection - the caller defines the pattern - but a pathological caller pattern
 * combined with a long discovered URL can still consume disproportionate CPU, since Java's
 * backtracking regex engine offers no reliable, safe mid-match cancellation). When at least one URL
 * filter pattern is configured, a candidate URL longer than {@link #MAX_CANDIDATE_URL_LENGTH} is
 * rejected before any pattern is evaluated against it, capping the worst-case input size a pattern
 * ever sees. This bounds attacker-controlled input length; it does <b>not</b> eliminate
 * catastrophic backtracking for a sufficiently pathological pattern evaluated at or under that
 * bound - see {@code docs/security-model.md#url-filter-pattern-safety} for the full residual-risk
 * statement. A crawl that configures no URL filter pattern at all is unaffected: there is nothing
 * here for a long URL to be evaluated against.
 */
public final class HostScopePolicy implements ICrawlScopePolicy {

    /**
     * Maximum candidate URL length evaluated against a configured URL filter pattern. Chosen well
     * above any realistic legitimate URL (common browser/server URL length ceilings are in the low
     * thousands of characters) while still giving pattern evaluation an explicit, finite worst-case
     * input size instead of an unbounded one. Not a claim that every pattern is safe up to this
     * length - see the class Javadoc.
     */
    static final int MAX_CANDIDATE_URL_LENGTH = 8_192;

    @Override
    public CrawlDecision evaluate(URI candidate, URI source, CrawlRequest request) {
        String scheme = candidate.getScheme();
        if (scheme == null || !request.allowedSchemes().contains(scheme.toLowerCase(Locale.ROOT))) {
            return CrawlDecision.reject(
                    CrawlDecisionType.REJECT_SCHEME, "scheme not allowed: " + scheme);
        }
        CrawlDecision hostDecision = evaluateHost(candidate, request);
        if (!hostDecision.allowed()) {
            return hostDecision;
        }
        boolean hasUrlFilters =
                !request.excludeUrlPatterns().isEmpty() || !request.includeUrlPatterns().isEmpty();
        String url = candidate.toString();
        // CRAWL-REGEX-001: reject before any pattern is evaluated, not after - see class Javadoc.
        if (hasUrlFilters && url.length() > MAX_CANDIDATE_URL_LENGTH) {
            return CrawlDecision.reject(
                    CrawlDecisionType.REJECT_URL_FILTER,
                    "candidate URL exceeds the maximum length evaluated by URL filters");
        }
        for (Pattern exclude : request.excludeUrlPatterns()) {
            if (exclude.matcher(url).find()) {
                // Never echoes exclude.pattern(): a caller-supplied filter pattern is not
                // guaranteed to be safe, non-sensitive diagnostic text any more than any other
                // caller-controlled value this project's safe-diagnostics convention protects.
                return CrawlDecision.reject(
                        CrawlDecisionType.REJECT_URL_FILTER, "matched an exclude URL pattern");
            }
        }
        if (!request.includeUrlPatterns().isEmpty()
                && request.includeUrlPatterns().stream().noneMatch(p -> p.matcher(url).find())) {
            return CrawlDecision.reject(
                    CrawlDecisionType.REJECT_URL_FILTER, "matched no include pattern");
        }
        return CrawlDecision.allow("in scope");
    }

    private static CrawlDecision evaluateHost(URI candidate, CrawlRequest request) {
        if (!request.sameHostOnly()) {
            return CrawlDecision.allow("host restriction disabled");
        }
        String host = candidate.getHost();
        if (host == null) {
            return CrawlDecision.reject(CrawlDecisionType.REJECT_HOST, "candidate has no host");
        }
        host = host.toLowerCase(Locale.ROOT);
        Set<String> allowedRoots = allowedHostRoots(request);
        if (allowedRoots.contains(host)) {
            return CrawlDecision.allow("host matches an allowed root");
        }
        if (!request.includeSubdomains()) {
            return CrawlDecision.reject(CrawlDecisionType.REJECT_HOST, "host not allowed: " + host);
        }
        for (String root : allowedRoots) {
            if (host.endsWith("." + root)) {
                return CrawlDecision.allow("host is a subdomain of an allowed root");
            }
        }
        return CrawlDecision.reject(
                CrawlDecisionType.REJECT_DOMAIN,
                "host is neither an allowed root nor one of its subdomains: " + host);
    }

    private static Set<String> allowedHostRoots(CrawlRequest request) {
        Set<String> roots = new LinkedHashSet<>(request.allowedHosts());
        for (URI seed : request.seeds()) {
            roots.add(seed.getHost().toLowerCase(Locale.ROOT));
        }
        return roots;
    }
}
