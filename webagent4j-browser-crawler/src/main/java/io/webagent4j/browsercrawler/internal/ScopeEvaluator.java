package io.webagent4j.browsercrawler.internal;

import io.webagent4j.browsercrawler.BrowserCrawlRequest;
import io.webagent4j.crawler.api.CrawlDecision;
import io.webagent4j.crawler.api.CrawlDecisionType;
import java.net.URI;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Scope decisions for a browser crawl, reusing {@code CrawlDecision}/{@code CrawlDecisionType} from
 * {@code webagent4j-crawler-api} unchanged - a decision is a decision regardless of backend.
 *
 * <p>The logic itself mirrors {@code HostScopePolicy} ({@code webagent4j-crawler}): scheme, then
 * host/domain scope, then URL filters, first failing check wins. Not literally the same class
 * because {@code HostScopePolicy} takes a concrete {@code CrawlRequest}; the domain-boundary rule
 * (a literal {@code "."} boundary, never a bare {@code endsWith}, so {@code evil-example.com} is
 * never accepted for {@code example.com}) is preserved exactly.
 */
public final class ScopeEvaluator {

    private ScopeEvaluator() {}

    public static CrawlDecision evaluate(URI candidate, BrowserCrawlRequest request) {
        String scheme = candidate.getScheme();
        if (scheme == null
                || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            return CrawlDecision.reject(
                    CrawlDecisionType.REJECT_SCHEME, "unsupported scheme: " + scheme);
        }
        CrawlDecision hostDecision = evaluateHost(candidate, request);
        if (!hostDecision.allowed()) {
            return hostDecision;
        }
        String urlString = candidate.toString();
        if (!request.includeUrlPatterns().isEmpty()
                && request.includeUrlPatterns().stream()
                        .noneMatch(pattern -> pattern.matcher(urlString).find())) {
            return CrawlDecision.reject(
                    CrawlDecisionType.REJECT_URL_FILTER, "matched no includeUrlPattern");
        }
        for (Pattern pattern : request.excludeUrlPatterns()) {
            if (pattern.matcher(urlString).find()) {
                return CrawlDecision.reject(
                        CrawlDecisionType.REJECT_URL_FILTER,
                        "matched excludeUrlPattern: " + pattern.pattern());
            }
        }
        return CrawlDecision.allow("in scope");
    }

    private static CrawlDecision evaluateHost(URI candidate, BrowserCrawlRequest request) {
        String host = candidate.getHost();
        if (host == null) {
            return CrawlDecision.reject(CrawlDecisionType.REJECT_HOST, "no host in URL");
        }
        host = host.toLowerCase(Locale.ROOT);
        if (!request.sameHostOnly() && request.allowedHosts().isEmpty()) {
            return CrawlDecision.allow("no host restriction configured");
        }
        Set<String> roots = allowedHostRoots(request);
        if (roots.contains(host)) {
            return CrawlDecision.allow("exact host match");
        }
        if (request.includeSubdomains()) {
            for (String root : roots) {
                if (host.endsWith("." + root)) {
                    return CrawlDecision.allow("subdomain of " + root);
                }
            }
        }
        return CrawlDecision.reject(CrawlDecisionType.REJECT_DOMAIN, "host out of scope: " + host);
    }

    private static Set<String> allowedHostRoots(BrowserCrawlRequest request) {
        Set<String> roots = new LinkedHashSet<>(request.allowedHosts());
        if (request.sameHostOnly()) {
            for (URI seed : request.seeds()) {
                String seedHost = seed.getHost();
                if (seedHost != null) {
                    roots.add(seedHost.toLowerCase(Locale.ROOT));
                }
            }
        }
        return roots;
    }
}
