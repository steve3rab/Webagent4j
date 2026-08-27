package io.webagent4j.browsercrawler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.webagent4j.browser.IBrowser;
import io.webagent4j.browser.IPage;
import io.webagent4j.browsercrawler.internal.LinkObservationFixtures;
import io.webagent4j.policy.PolicyDecision;
import io.webagent4j.policy.network.INetworkPolicy;
import io.webagent4j.policy.network.NetworkCheckPhase;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Proves network-destination governance on {@link BrowserCrawler#withNetworkPolicy}: a pre-
 * navigation deny means zero {@code IPage#navigate} calls, and a post-navigation violation (the
 * final URL, only checkable after a browser's own internal redirect already happened) leaves the
 * page treated as a failure - no observation, no link discovery - with navigation having genuinely
 * happened exactly once. Uses only scripted {@link IPage}/{@link IBrowser} mocks, never real
 * Playwright.
 */
class BrowserCrawlerNetworkPolicyTest {

    @Test
    void preNavigationDenyNeverCallsNavigate() {
        IPage page = scriptedPage("https://denied.example.test/", "Denied");
        IBrowser browser = browserReturning(page);
        INetworkPolicy denyAll = context -> PolicyDecision.deny("test.network.denied");
        BrowserCrawler crawler = new BrowserCrawler().withNetworkPolicy(denyAll);

        BrowserCrawlResult result =
                crawler.crawl(
                        BrowserCrawlRequest.builder(browser)
                                .seed("https://denied.example.test/")
                                .build());

        assertThat(result.pages()).isEmpty();
        assertThat(result.failures()).hasSize(1);
        assertThat(result.failures().get(0).type())
                .isEqualTo(BrowserCrawlFailureType.NETWORK_POLICY_DENIED);
        verify(page, never()).navigate(anyString(), any(Duration.class));
    }

    @Test
    void preNavigationAllowNavigatesExactlyOnce() {
        IPage page = scriptedPage("https://allowed.example.test/", "Allowed");
        IBrowser browser = browserReturning(page);
        INetworkPolicy allowAll = context -> PolicyDecision.allow("test.network.allowed");
        BrowserCrawler crawler = new BrowserCrawler().withNetworkPolicy(allowAll);

        BrowserCrawlResult result =
                crawler.crawl(
                        BrowserCrawlRequest.builder(browser)
                                .seed("https://allowed.example.test/")
                                .build());

        assertThat(result.pages()).hasSize(1);
        verify(page, times(1)).navigate(eq("https://allowed.example.test/"), any(Duration.class));
    }

    @Test
    void postNavigationViolationLeavesPageAsFailureWithNavigationHavingHappenedOnce() {
        // The browser navigates to the requested URL but its own internal redirect lands it on a
        // different final URL - exactly the scenario this framework cannot intercept mid-flight.
        IPage page = scriptedPage("https://redirected-to-denied.example.test/", "Redirected");
        IBrowser browser = browserReturning(page);
        List<NetworkCheckPhase> observedPhases = new ArrayList<>();
        INetworkPolicy allowFirstDenySecond =
                context -> {
                    observedPhases.add(context.phase());
                    return context.phase() == NetworkCheckPhase.POST_REQUEST
                            ? PolicyDecision.deny("test.network.redirect.denied")
                            : PolicyDecision.allow("test.network.allowed");
                };
        BrowserCrawler crawler = new BrowserCrawler().withNetworkPolicy(allowFirstDenySecond);

        BrowserCrawlResult result =
                crawler.crawl(
                        BrowserCrawlRequest.builder(browser)
                                .seed("https://start.example.test/")
                                .sameHostOnly(false)
                                .build());

        assertThat(result.pages()).isEmpty();
        assertThat(result.failures()).hasSize(1);
        assertThat(result.failures().get(0).type())
                .isEqualTo(BrowserCrawlFailureType.NETWORK_POLICY_VIOLATION);
        assertThat(observedPhases)
                .containsExactly(NetworkCheckPhase.PRE_REQUEST, NetworkCheckPhase.POST_REQUEST);
        verify(page, times(1)).navigate(anyString(), any(Duration.class));
    }

    @Test
    void unconfiguredNetworkPolicyLeavesExistingBehaviorUnchanged() {
        IPage page = scriptedPage("https://plain.example.test/", "Plain");
        IBrowser browser = browserReturning(page);
        BrowserCrawler crawler = new BrowserCrawler(); // no withNetworkPolicy(...)

        BrowserCrawlResult result =
                crawler.crawl(
                        BrowserCrawlRequest.builder(browser)
                                .seed("https://plain.example.test/")
                                .build());

        assertThat(result.pages()).hasSize(1);
    }

    /** Scripts {@code page.navigate(...)} to land on {@code finalUrl} with no links. */
    private static IPage scriptedPage(String finalUrl, String title) {
        IPage page = mock(IPage.class);
        doAnswer(
                        invocation -> {
                            when(page.url()).thenReturn(finalUrl);
                            when(page.title()).thenReturn(title);
                            when(page.observe(any()))
                                    .thenReturn(
                                            LinkObservationFixtures.withLinks(
                                                    finalUrl, List.of(), List.of()));
                            return null;
                        })
                .when(page)
                .navigate(anyString(), any(Duration.class));
        return page;
    }

    private static IBrowser browserReturning(IPage page) {
        IBrowser browser = mock(IBrowser.class);
        when(browser.newPage()).thenReturn(page);
        return browser;
    }
}
