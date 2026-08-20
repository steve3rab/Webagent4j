package io.webagent4j.crawler;

import static org.assertj.core.api.Assertions.assertThat;

import io.webagent4j.crawler.api.CrawlDecision;
import io.webagent4j.crawler.api.CrawlDecisionType;
import io.webagent4j.crawler.api.CrawlRequest;
import java.net.URI;
import org.junit.jupiter.api.Test;

class HostScopePolicyTest {

    private final HostScopePolicy policy = new HostScopePolicy();

    @Test
    void allowsAnHttpsLinkOnTheSeedHost() {
        CrawlRequest request = CrawlRequest.builder().seed("https://example.test/").build();

        CrawlDecision decision =
                policy.evaluate(
                        URI.create("https://example.test/products"),
                        URI.create("https://example.test/"),
                        request);

        assertThat(decision.allowed()).isTrue();
    }

    @Test
    void rejectsADisallowedScheme() {
        CrawlRequest request = CrawlRequest.builder().seed("https://example.test/").build();

        CrawlDecision decision =
                policy.evaluate(
                        URI.create("mailto:someone@example.test"),
                        URI.create("https://example.test/"),
                        request);

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.type()).isEqualTo(CrawlDecisionType.REJECT_SCHEME);
    }

    @Test
    void rejectsADifferentHostWhenSameHostOnly() {
        CrawlRequest request =
                CrawlRequest.builder().seed("https://example.test/").sameHostOnly(true).build();

        CrawlDecision decision =
                policy.evaluate(
                        URI.create("https://external.test/"),
                        URI.create("https://example.test/"),
                        request);

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.type()).isEqualTo(CrawlDecisionType.REJECT_HOST);
    }

    @Test
    void allowsAnyHostWhenSameHostOnlyDisabled() {
        CrawlRequest request =
                CrawlRequest.builder().seed("https://example.test/").sameHostOnly(false).build();

        CrawlDecision decision =
                policy.evaluate(
                        URI.create("https://external.test/"),
                        URI.create("https://example.test/"),
                        request);

        assertThat(decision.allowed()).isTrue();
    }

    @Test
    void allowsATrueSubdomainWhenIncludeSubdomainsEnabled() {
        CrawlRequest request =
                CrawlRequest.builder()
                        .seed("https://example.test/")
                        .sameHostOnly(true)
                        .includeSubdomains(true)
                        .build();

        CrawlDecision decision =
                policy.evaluate(
                        URI.create("https://www.example.test/"),
                        URI.create("https://example.test/"),
                        request);

        assertThat(decision.allowed()).isTrue();
    }

    @Test
    void rejectsALookalikeDomainEvenWithIncludeSubdomainsEnabled() {
        CrawlRequest request =
                CrawlRequest.builder()
                        .seed("https://example.test/")
                        .sameHostOnly(true)
                        .includeSubdomains(true)
                        .build();

        CrawlDecision decision =
                policy.evaluate(
                        URI.create("https://evil-example.test/"),
                        URI.create("https://example.test/"),
                        request);

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.type()).isEqualTo(CrawlDecisionType.REJECT_DOMAIN);
    }

    @Test
    void rejectsASubdomainWhenIncludeSubdomainsDisabled() {
        CrawlRequest request =
                CrawlRequest.builder()
                        .seed("https://example.test/")
                        .sameHostOnly(true)
                        .includeSubdomains(false)
                        .build();

        CrawlDecision decision =
                policy.evaluate(
                        URI.create("https://www.example.test/"),
                        URI.create("https://example.test/"),
                        request);

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.type()).isEqualTo(CrawlDecisionType.REJECT_HOST);
    }

    @Test
    void multipleSeedsEachEstablishTheirOwnAllowedHostRoot() {
        CrawlRequest request =
                CrawlRequest.builder().seed("https://a.test/").seed("https://b.test/").build();

        CrawlDecision fromA =
                policy.evaluate(
                        URI.create("https://b.test/x"), URI.create("https://a.test/"), request);
        CrawlDecision fromB =
                policy.evaluate(
                        URI.create("https://a.test/x"), URI.create("https://b.test/"), request);

        assertThat(fromA.allowed()).isTrue();
        assertThat(fromB.allowed()).isTrue();
    }

    @Test
    void rejectsAUrlMatchingAnExcludePattern() {
        CrawlRequest request =
                CrawlRequest.builder()
                        .seed("https://example.test/")
                        .excludeUrlPattern("/private/")
                        .build();

        CrawlDecision decision =
                policy.evaluate(
                        URI.create("https://example.test/private/secret"),
                        URI.create("https://example.test/"),
                        request);

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.type()).isEqualTo(CrawlDecisionType.REJECT_URL_FILTER);
    }

    @Test
    void rejectsAUrlMatchingNoIncludePatternWhenIncludePatternsArePresent() {
        CrawlRequest request =
                CrawlRequest.builder()
                        .seed("https://example.test/")
                        .includeUrlPattern("/blog/")
                        .build();

        CrawlDecision decision =
                policy.evaluate(
                        URI.create("https://example.test/shop/item"),
                        URI.create("https://example.test/"),
                        request);

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.type()).isEqualTo(CrawlDecisionType.REJECT_URL_FILTER);
    }
}
