package io.webagent4j.crawler.api;

import java.util.Objects;

/**
 * Immutable outcome of evaluating one candidate URL against crawl scope/policy: either {@link
 * CrawlDecisionType#ALLOW}, or a specific rejection type with a human-readable reason - never a
 * bare boolean a caller would have to reverse-engineer.
 *
 * @param type the decision
 * @param reason a diagnostic explanation, always present even for {@link CrawlDecisionType#ALLOW}
 */
public record CrawlDecision(CrawlDecisionType type, String reason) {

    /** Validates required fields. */
    public CrawlDecision {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(reason, "reason");
    }

    /** Returns an {@link CrawlDecisionType#ALLOW} decision with the given reason. */
    public static CrawlDecision allow(String reason) {
        return new CrawlDecision(CrawlDecisionType.ALLOW, reason);
    }

    /** Returns a rejection decision. {@code type} must not be {@link CrawlDecisionType#ALLOW}. */
    public static CrawlDecision reject(CrawlDecisionType type, String reason) {
        Objects.requireNonNull(type, "type");
        if (type == CrawlDecisionType.ALLOW) {
            throw new IllegalArgumentException("reject() cannot be called with ALLOW");
        }
        return new CrawlDecision(type, reason);
    }

    /** Returns whether this decision allows the candidate into the frontier. */
    public boolean allowed() {
        return type == CrawlDecisionType.ALLOW;
    }
}
