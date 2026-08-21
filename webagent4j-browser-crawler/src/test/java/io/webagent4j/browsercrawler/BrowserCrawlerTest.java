package io.webagent4j.browsercrawler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.webagent4j.browser.IBrowser;
import io.webagent4j.browser.IPage;
import io.webagent4j.browsercrawler.internal.LinkObservationFixtures;
import io.webagent4j.observation.ObservationTruncation;
import io.webagent4j.observation.ObservationTruncationType;
import io.webagent4j.observation.SemanticElement;
import io.webagent4j.wait.IMonotonicClock;
import io.webagent4j.wait.IWaitSleeper;
import io.webagent4j.wait.WaitEngine;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Engine-level tests using scripted {@link IPage} mocks (never real Playwright/network) and a fake
 * clock so navigation-timeout budget math is deterministic and instant - no {@code Thread.sleep}
 * anywhere in this suite.
 *
 * <p>Since {@link io.webagent4j.browsercrawler.internal.PageStabilityWaiter} delegates the actual
 * "stable for how long" timing to {@link IPage#waitForCondition}, which a plain mock cannot
 * meaningfully simulate (there is no real backend polling loop or JS clock behind it), this suite
 * proves the stability-budget contract two different ways: that {@code waitForCondition} receives
 * the correctly-reduced remaining budget (shared-budget math, verified via argument capture) and
 * that a thrown {@link io.webagent4j.browser.ConditionTimeoutException} is classified as {@link
 * BrowserCrawlFailureType#PAGE_STABILITY_TIMEOUT}. Real timing behavior - including the
 * client-side-navigation-during-stability race this design exists to bound - is proven against real
 * Playwright in {@code BrowserCrawlerRobustnessIT}.
 */
class BrowserCrawlerTest {

    private record PageScript(
            String finalUrl,
            String title,
            List<SemanticElement> links,
            RuntimeException failure,
            List<ObservationTruncation> truncations,
            Runnable onNavigate) {
        static PageScript ok(String finalUrl, String title, List<SemanticElement> links) {
            return new PageScript(finalUrl, title, links, null, List.of(), () -> {});
        }

        static PageScript failing(RuntimeException failure) {
            return new PageScript(null, null, List.of(), failure, List.of(), () -> {});
        }

        static PageScript truncated(
                String finalUrl,
                String title,
                List<SemanticElement> links,
                List<ObservationTruncation> truncations) {
            return new PageScript(finalUrl, title, links, null, truncations, () -> {});
        }

        /** Same as {@link #ok}, but runs {@code onNavigate} as a side effect of navigating here. */
        static PageScript okWithSideEffect(
                String finalUrl, String title, List<SemanticElement> links, Runnable onNavigate) {
            return new PageScript(finalUrl, title, links, null, List.of(), onNavigate);
        }
    }

    /** A fake clock + immediately-advancing sleeper, so every wait in the test is instant. */
    private static final class FakeTime implements IMonotonicClock, IWaitSleeper {
        private long nanos;

        @Override
        public long nanoTime() {
            return nanos;
        }

        @Override
        public void sleep(Duration duration) {
            nanos += duration.toNanos();
        }
    }

    private final Map<String, PageScript> scripts = new HashMap<>();
    private final List<IPage> createdPages = new ArrayList<>();
    private final FakeTime fakeTime = new FakeTime();
    private final BrowserCrawler crawler = new BrowserCrawler(new WaitEngine(fakeTime, fakeTime));

    private IPage newScriptedPage() {
        IPage page = mock(IPage.class);
        doAnswer(
                        invocation -> {
                            String requestedUrl = invocation.getArgument(0);
                            PageScript script = scripts.get(requestedUrl);
                            if (script == null) {
                                throw new IllegalStateException("unscripted URL: " + requestedUrl);
                            }
                            script.onNavigate().run();
                            if (script.failure() != null) {
                                throw script.failure();
                            }
                            when(page.url()).thenReturn(script.finalUrl());
                            when(page.title()).thenReturn(script.title());
                            // page.waitForCondition(...) is void; a Mockito mock already no-ops on
                            // an unstubbed void call, which is exactly "stability succeeded
                            // immediately" here.
                            when(page.observe(any()))
                                    .thenReturn(
                                            LinkObservationFixtures.withLinks(
                                                    script.finalUrl(),
                                                    script.links(),
                                                    script.truncations()));
                            return null;
                        })
                .when(page)
                .navigate(anyString(), any(Duration.class));
        return page;
    }

    private IBrowser scriptedBrowser() {
        IBrowser browser = mock(IBrowser.class);
        when(browser.newPage())
                .thenAnswer(
                        invocation -> {
                            IPage page = newScriptedPage();
                            createdPages.add(page);
                            return page;
                        });
        return browser;
    }

    private BrowserCrawlRequest.Builder requestFor(IBrowser browser, String seed) {
        return BrowserCrawlRequest.builder(browser)
                .seed(seed)
                .navigationTimeout(Duration.ofSeconds(5))
                .stabilityWindow(Duration.ofMillis(200));
    }

    @Test
    void singleSeedWithNoLinksProducesOnePage() {
        IBrowser browser = scriptedBrowser();
        scripts.put(
                "https://example.com/", PageScript.ok("https://example.com/", "Home", List.of()));

        BrowserCrawlResult result =
                crawler.crawl(requestFor(browser, "https://example.com/").build());

        assertThat(result.pages()).hasSize(1);
        assertThat(result.pages().get(0).finalUrl()).isEqualTo(URI.create("https://example.com/"));
        assertThat(result.pages().get(0).title()).contains("Home");
        assertThat(result.failures()).isEmpty();
        assertThat(result.terminationReason()).isEqualTo(BrowserCrawlTerminationReason.COMPLETED);
        assertThat(result.statistics().successfulPages()).isEqualTo(1);
    }

    @Test
    void discoveredLinksAreClaimedAndNavigatedInBfsOrder() {
        IBrowser browser = scriptedBrowser();
        scripts.put(
                "https://example.com/",
                PageScript.ok(
                        "https://example.com/",
                        "Home",
                        List.of(
                                LinkObservationFixtures.linkElement(
                                        1, "/a", "https://example.com/a", "A"),
                                LinkObservationFixtures.linkElement(
                                        2, "/b", "https://example.com/b", "B"))));
        scripts.put(
                "https://example.com/a", PageScript.ok("https://example.com/a", "A", List.of()));
        scripts.put(
                "https://example.com/b", PageScript.ok("https://example.com/b", "B", List.of()));

        BrowserCrawlResult result =
                crawler.crawl(requestFor(browser, "https://example.com/").build());

        assertThat(result.pages())
                .extracting(p -> p.finalUrl().toString())
                .containsExactly(
                        "https://example.com/", "https://example.com/a", "https://example.com/b");
        assertThat(result.statistics().discoveredUrls()).isEqualTo(2);
        assertThat(result.statistics().maxDepthReached()).isEqualTo(1);
    }

    @Test
    void maxDepthStopsFurtherDiscovery() {
        IBrowser browser = scriptedBrowser();
        scripts.put(
                "https://example.com/",
                PageScript.ok(
                        "https://example.com/",
                        "Home",
                        List.of(
                                LinkObservationFixtures.linkElement(
                                        1, "/a", "https://example.com/a", "A"))));
        scripts.put(
                "https://example.com/a",
                PageScript.ok(
                        "https://example.com/a",
                        "A",
                        List.of(
                                LinkObservationFixtures.linkElement(
                                        1, "/b", "https://example.com/b", "B"))));

        BrowserCrawlResult result =
                crawler.crawl(requestFor(browser, "https://example.com/").maxDepth(1).build());

        assertThat(result.pages())
                .extracting(p -> p.finalUrl().toString())
                .containsExactly("https://example.com/", "https://example.com/a");
        assertThat(result.rejectedUrls())
                .anySatisfy(
                        link ->
                                assertThat(link.resolvedUrl().toString())
                                        .isEqualTo("https://example.com/b"));
    }

    @Test
    void maxPagesStopsClaimingAndIsReportedAsTerminationReason() {
        IBrowser browser = scriptedBrowser();
        List<SemanticElement> manyLinks = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            String url = "https://example.com/p" + i;
            manyLinks.add(LinkObservationFixtures.linkElement(i + 1, "/p" + i, url, "P" + i));
            scripts.put(url, PageScript.ok(url, "P" + i, List.of()));
        }
        scripts.put(
                "https://example.com/", PageScript.ok("https://example.com/", "Home", manyLinks));

        BrowserCrawlResult result =
                crawler.crawl(requestFor(browser, "https://example.com/").maxPages(3).build());

        assertThat(result.pages()).hasSize(3);
        assertThat(result.terminationReason())
                .isEqualTo(BrowserCrawlTerminationReason.MAX_PAGES_REACHED);
        assertThat(result.statistics().claimedNavigations()).isEqualTo(3);
    }

    @Test
    void duplicateLinksFromDifferentParentsAreClaimedOnlyOnce() {
        IBrowser browser = scriptedBrowser();
        SemanticElement toShared =
                LinkObservationFixtures.linkElement(
                        1, "/shared", "https://example.com/shared", "Shared");
        scripts.put(
                "https://example.com/",
                PageScript.ok(
                        "https://example.com/",
                        "Home",
                        List.of(
                                LinkObservationFixtures.linkElement(
                                        1, "/a", "https://example.com/a", "A"),
                                LinkObservationFixtures.linkElement(
                                        2, "/b", "https://example.com/b", "B"))));
        scripts.put(
                "https://example.com/a",
                PageScript.ok("https://example.com/a", "A", List.of(toShared)));
        scripts.put(
                "https://example.com/b",
                PageScript.ok("https://example.com/b", "B", List.of(toShared)));
        scripts.put(
                "https://example.com/shared",
                PageScript.ok("https://example.com/shared", "Shared", List.of()));

        BrowserCrawlResult result =
                crawler.crawl(requestFor(browser, "https://example.com/").build());

        long sharedNavigations =
                result.pages().stream()
                        .filter(p -> p.finalUrl().toString().endsWith("/shared"))
                        .count();
        assertThat(sharedNavigations).isEqualTo(1);
        assertThat(result.statistics().duplicateUrls()).isEqualTo(1);
    }

    @Test
    void outOfScopeDiscoveredLinkIsRejectedNotNavigated() {
        IBrowser browser = scriptedBrowser();
        scripts.put(
                "https://example.com/",
                PageScript.ok(
                        "https://example.com/",
                        "Home",
                        List.of(
                                LinkObservationFixtures.linkElement(
                                        1,
                                        "https://other.example/",
                                        "https://other.example/",
                                        "Other"))));

        BrowserCrawlResult result =
                crawler.crawl(requestFor(browser, "https://example.com/").build());

        assertThat(result.pages()).hasSize(1);
        assertThat(result.rejectedUrls()).hasSize(1);
        assertThat(result.rejectedUrls().get(0).allowed()).isFalse();
        assertThat(result.statistics().outOfScopeUrls()).isEqualTo(1);
    }

    /**
     * AREA-UNIT: an {@code <area href>}-sourced link's {@link
     * io.webagent4j.crawler.api.LinkKind#AREA} provenance survives both the accepted/claimed path
     * and the out-of-scope-rejected path - {@code BrowserCrawler} must never default either to
     * {@code ANCHOR}.
     */
    @Test
    void areaSourcedLinksPreserveAreaKindWhenAcceptedAndWhenRejected() {
        IBrowser browser = scriptedBrowser();
        scripts.put(
                "https://example.com/",
                PageScript.ok(
                        "https://example.com/",
                        "Home",
                        List.of(
                                LinkObservationFixtures.areaElement(
                                        1, "/area-target", "https://example.com/area-target", "T"),
                                LinkObservationFixtures.areaElement(
                                        2,
                                        "https://other.example/",
                                        "https://other.example/",
                                        "Other"))));
        scripts.put(
                "https://example.com/area-target",
                PageScript.ok("https://example.com/area-target", "Target", List.of()));

        BrowserCrawlResult result =
                crawler.crawl(requestFor(browser, "https://example.com/").build());

        assertThat(result.pages()).hasSize(2);
        assertThat(result.pages().get(0).links())
                .filteredOn(link -> link.resolvedUrl().toString().endsWith("/area-target"))
                .singleElement()
                .satisfies(
                        link -> {
                            assertThat(link.kind())
                                    .isEqualTo(io.webagent4j.crawler.api.LinkKind.AREA);
                            assertThat(link.allowed()).isTrue();
                        });
        assertThat(result.rejectedUrls())
                .singleElement()
                .satisfies(
                        link -> {
                            assertThat(link.kind())
                                    .isEqualTo(io.webagent4j.crawler.api.LinkKind.AREA);
                            assertThat(link.allowed()).isFalse();
                        });
    }

    @Test
    void navigationLeavingScopeOnFinalUrlIsAFailureNotAPage() {
        IBrowser browser = scriptedBrowser();
        scripts.put(
                "https://example.com/",
                PageScript.ok("https://other.example/redirected", "Redirected", List.of()));

        BrowserCrawlResult result =
                crawler.crawl(requestFor(browser, "https://example.com/").build());

        assertThat(result.pages()).isEmpty();
        assertThat(result.failures()).hasSize(1);
        assertThat(result.failures().get(0).type())
                .isEqualTo(BrowserCrawlFailureType.OUT_OF_SCOPE_REDIRECT);
    }

    @Test
    void navigationExceptionBecomesAStructuredFailure() {
        IBrowser browser = scriptedBrowser();
        scripts.put("https://example.com/", PageScript.failing(new RuntimeException("boom")));

        BrowserCrawlResult result =
                crawler.crawl(requestFor(browser, "https://example.com/").build());

        assertThat(result.pages()).isEmpty();
        assertThat(result.failures()).hasSize(1);
        assertThat(result.failures().get(0).type())
                .isEqualTo(BrowserCrawlFailureType.NAVIGATION_FAILED);
        assertThat(result.failures().get(0).cause()).isPresent();
    }

    /**
     * TIMEOUT-003: the typed {@link io.webagent4j.browser.NavigationTimeoutException} - never a
     * budget-expiry inference, never a backend-specific message match - is what classifies a
     * navigation as {@link BrowserCrawlFailureType#NAVIGATION_TIMEOUT}.
     */
    @Test
    void typedNavigationTimeoutExceptionBecomesNavigationTimeoutFailure() {
        IBrowser browser = scriptedBrowser();
        scripts.put(
                "https://example.com/",
                PageScript.failing(
                        new io.webagent4j.browser.NavigationTimeoutException(
                                "did not commit in time")));

        BrowserCrawlResult result =
                crawler.crawl(requestFor(browser, "https://example.com/").build());

        assertThat(result.pages()).isEmpty();
        assertThat(result.failures()).hasSize(1);
        assertThat(result.failures().get(0).type())
                .isEqualTo(BrowserCrawlFailureType.NAVIGATION_TIMEOUT);
        assertThat(result.failures().get(0).cause()).isPresent();
    }

    @Test
    void failFastStopsAfterAFatalFailure() {
        IBrowser browser = scriptedBrowser();
        scripts.put(
                "https://example.com/",
                PageScript.ok(
                        "https://example.com/",
                        "Home",
                        List.of(
                                LinkObservationFixtures.linkElement(
                                        1, "/bad", "https://example.com/bad", "Bad"),
                                LinkObservationFixtures.linkElement(
                                        2, "/ok", "https://example.com/ok", "Ok"))));
        scripts.put("https://example.com/bad", PageScript.failing(new RuntimeException("boom")));
        scripts.put(
                "https://example.com/ok", PageScript.ok("https://example.com/ok", "Ok", List.of()));

        BrowserCrawlResult result =
                crawler.crawl(
                        requestFor(browser, "https://example.com/")
                                .maxConcurrency(1)
                                .failFast(true)
                                .build());

        assertThat(result.terminationReason()).isEqualTo(BrowserCrawlTerminationReason.FAIL_FAST);
        assertThat(result.pages())
                .extracting(p -> p.finalUrl().toString())
                .doesNotContain("https://example.com/ok");
    }

    @Test
    void nonFailFastContinuesAfterAFailure() {
        IBrowser browser = scriptedBrowser();
        scripts.put(
                "https://example.com/",
                PageScript.ok(
                        "https://example.com/",
                        "Home",
                        List.of(
                                LinkObservationFixtures.linkElement(
                                        1, "/bad", "https://example.com/bad", "Bad"),
                                LinkObservationFixtures.linkElement(
                                        2, "/ok", "https://example.com/ok", "Ok"))));
        scripts.put("https://example.com/bad", PageScript.failing(new RuntimeException("boom")));
        scripts.put(
                "https://example.com/ok", PageScript.ok("https://example.com/ok", "Ok", List.of()));

        BrowserCrawlResult result =
                crawler.crawl(requestFor(browser, "https://example.com/").failFast(false).build());

        assertThat(result.terminationReason()).isEqualTo(BrowserCrawlTerminationReason.COMPLETED);
        assertThat(result.pages())
                .extracting(p -> p.finalUrl().toString())
                .contains("https://example.com/ok");
        assertThat(result.failures()).hasSize(1);
    }

    @Test
    void cancellationBeforeCrawlStartsClaimsNothingAndNeverOpensAPage() {
        IBrowser browser = scriptedBrowser();
        scripts.put(
                "https://example.com/", PageScript.ok("https://example.com/", "Home", List.of()));
        CancellationToken token = CancellationToken.create();
        token.cancel();

        BrowserCrawlResult result =
                crawler.crawl(
                        requestFor(browser, "https://example.com/")
                                .cancellationToken(token)
                                .build());

        assertThat(result.pages()).isEmpty();
        assertThat(result.failures()).isEmpty();
        assertThat(result.terminationReason()).isEqualTo(BrowserCrawlTerminationReason.CANCELLED);
        assertThat(result.statistics().cancelledTasks()).isEqualTo(0);
        assertThat(result.statistics().claimedNavigations()).isEqualTo(0);
        verify(browser, times(0)).newPage();
    }

    @Test
    void cancellationObservedDuringNavigationPreventsDiscoveredChildrenFromBeingClaimed() {
        IBrowser browser = scriptedBrowser();
        CancellationToken token = CancellationToken.create();
        List<SemanticElement> manyLinks = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            String url = "https://example.com/p" + i;
            manyLinks.add(LinkObservationFixtures.linkElement(i + 1, "/p" + i, url, "P" + i));
        }
        // Simulates cancel() being called from another thread while this navigation is in flight:
        // deterministic in this single-threaded test because the crawler itself only ever calls
        // into the page from its own thread, so running this as a navigate() side effect reliably
        // makes cancellation observed exactly once the seed's own navigation has committed.
        scripts.put(
                "https://example.com/",
                PageScript.okWithSideEffect(
                        "https://example.com/", "Home", manyLinks, token::cancel));

        BrowserCrawlResult result =
                crawler.crawl(
                        requestFor(browser, "https://example.com/")
                                .cancellationToken(token)
                                .build());

        assertThat(result.pages())
                .extracting(p -> p.finalUrl().toString())
                .containsExactly("https://example.com/");
        assertThat(result.terminationReason()).isEqualTo(BrowserCrawlTerminationReason.CANCELLED);
        assertThat(result.statistics().claimedNavigations()).isEqualTo(1);
        assertThat(result.rejectedUrls()).hasSize(5);
        assertThat(result.rejectedUrls())
                .allSatisfy(
                        link ->
                                assertThat(link.rejection().map(d -> d.type()).orElseThrow())
                                        .isEqualTo(
                                                io.webagent4j.crawler.api.CrawlDecisionType
                                                        .REJECT_CANCELLED));
        verify(browser, times(1)).newPage();
    }

    @Test
    void crawlerOwnedPagesAreAlwaysClosed() throws Exception {
        IBrowser browser = scriptedBrowser();
        scripts.put(
                "https://example.com/", PageScript.ok("https://example.com/", "Home", List.of()));

        crawler.crawl(requestFor(browser, "https://example.com/").build());

        assertThat(createdPages).hasSize(1);
        verify(createdPages.get(0), times(1)).close();
    }

    @Test
    void callerOwnedBrowserIsNotClosedByDefault() throws Exception {
        IBrowser browser = scriptedBrowser();
        scripts.put(
                "https://example.com/", PageScript.ok("https://example.com/", "Home", List.of()));

        crawler.crawl(requestFor(browser, "https://example.com/").build());

        verify(browser, times(0)).close();
    }

    @Test
    void closeBrowserOnCompletionClosesTheBrowser() throws Exception {
        IBrowser browser = scriptedBrowser();
        scripts.put(
                "https://example.com/", PageScript.ok("https://example.com/", "Home", List.of()));

        crawler.crawl(
                requestFor(browser, "https://example.com/").closeBrowserOnCompletion(true).build());

        verify(browser, times(1)).close();
    }

    @Test
    void navigateReceivesTheRemainingNavigationTimeoutBudgetNotABackendDefault() {
        IBrowser browser = scriptedBrowser();
        scripts.put(
                "https://example.com/", PageScript.ok("https://example.com/", "Home", List.of()));

        crawler.crawl(
                requestFor(browser, "https://example.com/")
                        .navigationTimeout(Duration.ofSeconds(7))
                        .build());

        ArgumentCaptor<Duration> timeoutCaptor = ArgumentCaptor.forClass(Duration.class);
        verify(createdPages.get(0)).navigate(anyString(), timeoutCaptor.capture());
        // No time has elapsed on the fake clock between WaitBudget.start() and this call, so the
        // remaining budget is exactly the configured navigationTimeout - proving BrowserCrawler
        // threads its own deadline into navigate() rather than letting a backend default apply.
        assertThat(timeoutCaptor.getValue()).isEqualTo(Duration.ofSeconds(7));
    }

    @Test
    void navigationConsumingMostOfTheBudgetLeavesOnlyTheRemainderForStability() {
        IBrowser browser = scriptedBrowser();
        // The navigate() call itself "spends" 900ms of fake time as a side effect, simulating a
        // slow real navigation - proving navigation and stability share one monotonic budget rather
        // than each independently getting the full configured navigationTimeout.
        scripts.put(
                "https://example.com/",
                PageScript.okWithSideEffect(
                        "https://example.com/",
                        "Home",
                        List.of(),
                        () -> fakeTime.sleep(Duration.ofMillis(900))));

        crawler.crawl(
                requestFor(browser, "https://example.com/")
                        .navigationTimeout(Duration.ofSeconds(1))
                        .stabilityWindow(Duration.ofMillis(200))
                        .build());

        // stabilityWaiter.awaitStable delegates the actual timing to the backend
        // (IPage#waitForCondition), which a mock cannot simulate - so the shared-budget contract is
        // proven here at the call-argument level: only the 100ms actually left in the 1s
        // navigationTimeout budget after navigate()'s 900ms side effect, never a fresh independent
        // timeout for the stability stage.
        ArgumentCaptor<Duration> timeoutCaptor = ArgumentCaptor.forClass(Duration.class);
        verify(createdPages.get(0)).waitForCondition(anyString(), timeoutCaptor.capture());
        assertThat(timeoutCaptor.getValue()).isEqualTo(Duration.ofMillis(100));
    }

    /**
     * When navigation itself consumes the entire shared budget, {@code PageStabilityWaiter} must
     * never attempt a backend call at all (see its {@code awaitStable} contract) - it synthesizes
     * its own {@link io.webagent4j.browser.ConditionTimeoutException} with no cause, still
     * resulting in {@link BrowserCrawlFailureType#PAGE_STABILITY_TIMEOUT}, distinguishable from a
     * genuine backend-observed timeout only by that absent cause chain.
     */
    @Test
    void navigationConsumingTheEntireBudgetNeverAttemptsAStabilityBackendCall() {
        IBrowser browser = scriptedBrowser();
        scripts.put(
                "https://example.com/",
                PageScript.okWithSideEffect(
                        "https://example.com/",
                        "Home",
                        List.of(),
                        () -> fakeTime.sleep(Duration.ofSeconds(1))));

        BrowserCrawlResult result =
                crawler.crawl(
                        requestFor(browser, "https://example.com/")
                                .navigationTimeout(Duration.ofSeconds(1))
                                .stabilityWindow(Duration.ofMillis(200))
                                .build());

        assertThat(result.failures()).hasSize(1);
        assertThat(result.failures().get(0).type())
                .isEqualTo(BrowserCrawlFailureType.PAGE_STABILITY_TIMEOUT);
        assertThat(result.failures().get(0).cause())
                .get()
                .satisfies(cause -> assertThat(cause.getCause()).isNull());
        verify(createdPages.get(0), org.mockito.Mockito.never())
                .waitForCondition(anyString(), any(Duration.class));
    }

    /**
     * STAB-UNIT-001: a {@link io.webagent4j.browser.ConditionTimeoutException} thrown from {@link
     * IPage#waitForCondition} - the typed signal a backend's own native, natively-bounded polling
     * primitive gives when the condition never becomes true in time - is what classifies a
     * navigation as {@link BrowserCrawlFailureType#PAGE_STABILITY_TIMEOUT}, and its typed cause is
     * preserved into {@link BrowserCrawlFailure#cause()} rather than discarded.
     */
    @Test
    void stabilityConditionTimeoutBecomesPageStabilityTimeoutFailure() {
        IBrowser browser = mock(IBrowser.class);
        IPage page = mock(IPage.class);
        when(browser.newPage()).thenReturn(page);
        when(page.url()).thenReturn("https://example.com/");
        io.webagent4j.browser.ConditionTimeoutException timeout =
                new io.webagent4j.browser.ConditionTimeoutException("not stable");
        doThrow(timeout).when(page).waitForCondition(anyString(), any(Duration.class));

        BrowserCrawlResult result =
                crawler.crawl(requestFor(browser, "https://example.com/").build());

        assertThat(result.pages()).isEmpty();
        assertThat(result.failures()).hasSize(1);
        assertThat(result.failures().get(0).type())
                .isEqualTo(BrowserCrawlFailureType.PAGE_STABILITY_TIMEOUT);
        assertThat(result.failures().get(0).cause()).contains(timeout);
    }

    /**
     * STAB-UNIT-002: an unsupported backend - {@link IPage#waitForCondition}'s own default throws
     * {@link UnsupportedOperationException} - is classified as {@link
     * BrowserCrawlFailureType#BROWSER_BACKEND_FAILURE}, not misclassified as a stability timeout.
     */
    @Test
    void unsupportedStabilityBackendBecomesBrowserBackendFailure() {
        IBrowser browser = mock(IBrowser.class);
        IPage page = mock(IPage.class);
        when(browser.newPage()).thenReturn(page);
        when(page.url()).thenReturn("https://example.com/");
        doThrow(new UnsupportedOperationException("not supported"))
                .when(page)
                .waitForCondition(anyString(), any(Duration.class));

        BrowserCrawlResult result =
                crawler.crawl(requestFor(browser, "https://example.com/").build());

        assertThat(result.pages()).isEmpty();
        assertThat(result.failures()).hasSize(1);
        assertThat(result.failures().get(0).type())
                .isEqualTo(BrowserCrawlFailureType.BROWSER_BACKEND_FAILURE);
    }

    @Test
    void observationTruncatedBecomesAFailureNeverASilentIncompleteSuccess() {
        IBrowser browser = scriptedBrowser();
        ObservationTruncation truncation =
                new ObservationTruncation(
                        ObservationTruncationType.ELEMENTS, 5000, 2000, Optional.empty());
        scripts.put(
                "https://example.com/",
                PageScript.truncated(
                        "https://example.com/", "Home", List.of(), List.of(truncation)));

        BrowserCrawlResult result =
                crawler.crawl(requestFor(browser, "https://example.com/").build());

        assertThat(result.pages()).isEmpty();
        assertThat(result.failures()).hasSize(1);
        assertThat(result.failures().get(0).type())
                .isEqualTo(BrowserCrawlFailureType.OBSERVATION_TRUNCATED);
        assertThat(result.failures().get(0).message()).contains("ELEMENTS").contains("2000/5000");
    }

    @Test
    void navigatesOnlyOnePageAtATimeAndDiscoversAllOfThem() {
        IBrowser browser = scriptedBrowser();
        List<SemanticElement> seedLinks = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            String url = "https://example.com/p" + i;
            seedLinks.add(LinkObservationFixtures.linkElement(i + 1, "/p" + i, url, "P" + i));
            scripts.put(url, PageScript.ok(url, "P" + i, List.of()));
        }
        scripts.put(
                "https://example.com/", PageScript.ok("https://example.com/", "Home", seedLinks));

        BrowserCrawlResult result =
                crawler.crawl(requestFor(browser, "https://example.com/").build());

        assertThat(result.pages()).hasSize(7);
        // exactly one page for the whole crawl - reused for every navigation, never one per task
        verify(browser, times(1)).newPage();
    }

    /**
     * Regression test for the concurrency bug this design replaced: the original Phase 0.7 engine
     * created one {@code IPage} per worker thread via {@code ThreadLocal} and navigated them from a
     * bounded {@code ExecutorService}, which called {@link IBrowser#newPage()} and every {@link
     * IPage} operation concurrently from multiple Java threads. Neither {@link IBrowser} nor {@link
     * IPage} is documented as thread-safe; under real Playwright this silently corrupted a crawl (a
     * discovered page vanished - see {@code BrowserCrawlerIT}'s former {@code
     * boundedConcurrencyCompletesTheSameCrawlAsSequential} failure). This test proves the fix
     * structurally: every backend call the engine makes, across a whole multi-page crawl, happens
     * on the exact same thread that called {@link BrowserCrawler#crawl}.
     */
    @Test
    void everyBackendCallHappensOnTheSingleCallingThread() {
        long callingThreadId = Thread.currentThread().threadId();
        List<Long> observedThreadIds = new ArrayList<>();
        IBrowser browser = mock(IBrowser.class);
        when(browser.newPage())
                .thenAnswer(
                        invocation -> {
                            observedThreadIds.add(Thread.currentThread().threadId());
                            IPage page = mock(IPage.class);
                            when(page.url()).thenReturn("https://example.com/");
                            when(page.title()).thenReturn("Home");
                            // page.waitForCondition(...) is void; an unstubbed mock call already
                            // no-ops, which is exactly "stability succeeded immediately" here.
                            when(page.observe(any()))
                                    .thenReturn(
                                            LinkObservationFixtures.withLinks(
                                                    "https://example.com/", List.of()));
                            doAnswer(
                                            nav -> {
                                                observedThreadIds.add(
                                                        Thread.currentThread().threadId());
                                                return null;
                                            })
                                    .when(page)
                                    .navigate(anyString(), any(Duration.class));
                            return page;
                        });

        crawler.crawl(requestFor(browser, "https://example.com/").build());

        assertThat(observedThreadIds).isNotEmpty();
        assertThat(observedThreadIds).allMatch(id -> id == callingThreadId);
    }
}
