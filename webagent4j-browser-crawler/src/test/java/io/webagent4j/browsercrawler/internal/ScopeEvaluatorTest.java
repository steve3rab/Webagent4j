package io.webagent4j.browsercrawler.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import io.webagent4j.browser.IBrowser;
import io.webagent4j.browsercrawler.BrowserCrawlRequest;
import io.webagent4j.crawler.api.CrawlDecision;
import io.webagent4j.crawler.api.CrawlDecisionType;
import java.net.URI;
import org.junit.jupiter.api.Test;

class ScopeEvaluatorTest {

    private final IBrowser browser = mock(IBrowser.class);

    @Test
    void nonHttpSchemeRejected() {
        BrowserCrawlRequest request =
                BrowserCrawlRequest.builder(browser).seed("https://example.com/").build();
        CrawlDecision decision =
                ScopeEvaluator.evaluate(URI.create("mailto:a@example.com"), request);
        assertThat(decision.allowed()).isFalse();
        assertThat(decision.type()).isEqualTo(CrawlDecisionType.REJECT_SCHEME);
    }

    @Test
    void sameHostAsSeedIsAllowed() {
        BrowserCrawlRequest request =
                BrowserCrawlRequest.builder(browser).seed("https://example.com/").build();
        CrawlDecision decision =
                ScopeEvaluator.evaluate(URI.create("https://example.com/page"), request);
        assertThat(decision.allowed()).isTrue();
    }

    @Test
    void differentHostRejectedByDefault() {
        BrowserCrawlRequest request =
                BrowserCrawlRequest.builder(browser).seed("https://example.com/").build();
        CrawlDecision decision =
                ScopeEvaluator.evaluate(URI.create("https://evil.example.com/"), request);
        assertThat(decision.allowed()).isFalse();
        assertThat(decision.type()).isEqualTo(CrawlDecisionType.REJECT_DOMAIN);
    }

    @Test
    void subdomainAllowedWhenIncludeSubdomainsTrue() {
        BrowserCrawlRequest request =
                BrowserCrawlRequest.builder(browser)
                        .seed("https://example.com/")
                        .includeSubdomains(true)
                        .build();
        CrawlDecision decision =
                ScopeEvaluator.evaluate(URI.create("https://blog.example.com/"), request);
        assertThat(decision.allowed()).isTrue();
    }

    @Test
    void lookalikeDomainNeverAcceptedAsSubdomain() {
        BrowserCrawlRequest request =
                BrowserCrawlRequest.builder(browser)
                        .seed("https://example.com/")
                        .includeSubdomains(true)
                        .build();
        CrawlDecision decision =
                ScopeEvaluator.evaluate(URI.create("https://evil-example.com/"), request);
        assertThat(decision.allowed()).isFalse();
    }

    @Test
    void excludeUrlPatternRejectsMatchingUrl() {
        BrowserCrawlRequest request =
                BrowserCrawlRequest.builder(browser)
                        .seed("https://example.com/")
                        .excludeUrlPattern("/private/")
                        .build();
        CrawlDecision decision =
                ScopeEvaluator.evaluate(URI.create("https://example.com/private/data"), request);
        assertThat(decision.allowed()).isFalse();
        assertThat(decision.type()).isEqualTo(CrawlDecisionType.REJECT_URL_FILTER);
    }

    @Test
    void includeUrlPatternRequiresAtLeastOneMatch() {
        BrowserCrawlRequest request =
                BrowserCrawlRequest.builder(browser)
                        .seed("https://example.com/")
                        .includeUrlPattern("/blog/")
                        .build();
        CrawlDecision decision =
                ScopeEvaluator.evaluate(URI.create("https://example.com/other"), request);
        assertThat(decision.allowed()).isFalse();
        assertThat(decision.type()).isEqualTo(CrawlDecisionType.REJECT_URL_FILTER);
    }
}
