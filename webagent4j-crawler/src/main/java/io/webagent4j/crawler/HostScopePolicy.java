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
 */
public final class HostScopePolicy implements ICrawlScopePolicy {

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
        String url = candidate.toString();
        for (Pattern exclude : request.excludeUrlPatterns()) {
            if (exclude.matcher(url).find()) {
                return CrawlDecision.reject(
                        CrawlDecisionType.REJECT_URL_FILTER,
                        "matched exclude pattern: " + exclude.pattern());
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
