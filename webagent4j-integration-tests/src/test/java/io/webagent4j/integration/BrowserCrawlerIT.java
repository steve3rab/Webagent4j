package io.webagent4j.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import io.webagent4j.browser.IBrowser;
import io.webagent4j.browsercrawler.BrowserCrawlFailureType;
import io.webagent4j.browsercrawler.BrowserCrawlRequest;
import io.webagent4j.browsercrawler.BrowserCrawlResult;
import io.webagent4j.browsercrawler.BrowserCrawlTerminationReason;
import io.webagent4j.browsercrawler.BrowserCrawledPage;
import io.webagent4j.browsercrawler.BrowserCrawler;
import io.webagent4j.browsercrawler.CancellationToken;
import io.webagent4j.core.WebAgent;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Real-Playwright integration tests for {@link BrowserCrawler}, against a deterministic local HTTP
 * fixture - no external websites, no arbitrary sleeps.
 */
class BrowserCrawlerIT {

    private static HttpServer server;
    private static ExecutorService serverExecutor;
    private static String baseUrl;

    private IBrowser browser;

    @BeforeAll
    static void startFixture() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/",
                exchange ->
                        respond(
                                exchange,
                                html("Home", "<a href=\"/a\">A</a><a href=\"/b\">B</a>")));
        server.createContext(
                "/a", exchange -> respond(exchange, html("A", "<a href=\"/c\">C</a>")));
        server.createContext("/b", exchange -> respond(exchange, html("B", "")));
        server.createContext("/c", exchange -> respond(exchange, html("C", "")));
        server.createContext(
                "/external",
                exchange ->
                        respond(
                                exchange,
                                html(
                                        "External",
                                        "<a href=\"https://out-of-scope.invalid/\">Out</a>")));
        server.createContext(
                "/dynamic",
                exchange ->
                        respond(
                                exchange,
                                """
                                <!doctype html>
                                <html><head><title>Dynamic</title></head>
                                <body>
                                <script>
                                  setTimeout(function() {
                                    var a = document.createElement('a');
                                    a.href = '/a';
                                    a.textContent = 'Late link';
                                    document.body.appendChild(a);
                                  }, 50);
                                </script>
                                </body></html>
                                """));
        server.createContext(
                "/wide",
                exchange ->
                        respond(
                                exchange,
                                html(
                                        "Wide",
                                        "<a href=\"/wide/1\">1</a><a href=\"/wide/2\">2</a>"
                                                + "<a href=\"/wide/3\">3</a><a href=\"/wide/4\">4</a>"
                                                + "<a href=\"/wide/5\">5</a><a href=\"/wide/6\">6</a>")));
        // Absolute hrefs deliberately - a bare relative href resolved against a path with no
        // trailing slash (like "/wide/1") replaces its last segment rather than descending under
        // it, per RFC 3986 5.3, so a bare "child" here would resolve to "/wide/child", not
        // "/wide/1/child".
        server.createContext(
                "/wide/1",
                exchange -> respond(exchange, html("Wide1", "<a href=\"/wide/1/child\">C</a>")));
        server.createContext(
                "/wide/2",
                exchange -> respond(exchange, html("Wide2", "<a href=\"/wide/2/child\">C</a>")));
        server.createContext("/wide/3", exchange -> respond(exchange, html("Wide3", "")));
        server.createContext("/wide/4", exchange -> respond(exchange, html("Wide4", "")));
        server.createContext("/wide/5", exchange -> respond(exchange, html("Wide5", "")));
        server.createContext("/wide/6", exchange -> respond(exchange, html("Wide6", "")));
        server.createContext(
                "/wide/1/child", exchange -> respond(exchange, html("Wide1Child", "")));
        server.createContext(
                "/wide/2/child", exchange -> respond(exchange, html("Wide2Child", "")));
        // The Playwright browser's own default navigation timeout is 30s (Timeouts.defaults()) -
        // this handler sleeps 5s, well under that default but well over the 1s navigationTimeout
        // the test below configures, so only an authoritative crawler-side timeout stops it early.
        server.createContext(
                "/hang",
                exchange -> {
                    try {
                        Thread.sleep(5000);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    respond(exchange, html("Hang", ""));
                });
        // More elements than the crawler's fixed 2000-element observation bound, with one more
        // link past that boundary that must never be discovered from a truncated snapshot.
        server.createContext(
                "/truncated",
                exchange -> {
                    StringBuilder body = new StringBuilder();
                    for (int i = 0; i < 2500; i++) {
                        body.append("<a href=\"/truncated/item")
                                .append(i)
                                .append("\">Item ")
                                .append(i)
                                .append("</a>");
                    }
                    body.append("<a href=\"/truncated/hidden\">Hidden</a>");
                    respond(exchange, html("Truncated", body.toString()));
                });
        server.createContext(
                "/truncated/hidden", exchange -> respond(exchange, html("Hidden", "")));
        server.createContext(
                "/session-start",
                exchange -> {
                    exchange.getResponseHeaders().add("Set-Cookie", "session=abc123; Path=/");
                    respond(
                            exchange,
                            html("SessionStart", "<a href=\"/session-only\">Session only</a>"));
                });
        server.createContext(
                "/session-only",
                exchange -> {
                    String cookieHeader = exchange.getRequestHeaders().getFirst("Cookie");
                    boolean authenticated =
                            cookieHeader != null && cookieHeader.contains("session=abc123");
                    respond(
                            exchange,
                            html(authenticated ? "Authenticated" : "Unauthenticated", ""));
                });
        serverExecutor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(serverExecutor);
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterAll
    static void stopFixture() {
        server.stop(0);
        serverExecutor.close();
    }

    @BeforeEach
    void launchBrowser() {
        browser = WebAgent.browser().playwright().chromium().headless(true).launch();
    }

    @AfterEach
    void closeBrowser() {
        browser.close();
    }

    private static String html(String title, String body) {
        return "<!doctype html><html><head><title>"
                + title
                + "</title></head><body>"
                + body
                + "</body></html>";
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, String body)
            throws IOException {
        byte[] bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
        exchange.sendResponseHeaders(200, bytes.length);
        try (var out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private BrowserCrawlRequest.Builder requestFor(String path) {
        return BrowserCrawlRequest.builder(browser)
                .seed(baseUrl + path)
                .navigationTimeout(Duration.ofSeconds(10))
                .stabilityWindow(Duration.ofMillis(300));
    }

    @Test
    void crawlsAllReachablePagesFollowingRelativeLinks() {
        BrowserCrawlResult result = new BrowserCrawler().crawl(requestFor("/").build());

        assertThat(result.pages())
                .extracting(p -> p.finalUrl().toString())
                .containsExactlyInAnyOrder(
                        baseUrl + "/", baseUrl + "/a", baseUrl + "/b", baseUrl + "/c");
        assertThat(result.terminationReason()).isEqualTo(BrowserCrawlTerminationReason.COMPLETED);
        assertThat(result.failures()).isEmpty();
    }

    @Test
    void maxDepthBoundsTraversal() {
        BrowserCrawlResult result = new BrowserCrawler().crawl(requestFor("/").maxDepth(1).build());

        assertThat(result.pages())
                .extracting(p -> p.finalUrl().toString())
                .containsExactlyInAnyOrder(baseUrl + "/", baseUrl + "/a", baseUrl + "/b");
    }

    @Test
    void maxPagesBoundsTraversal() {
        BrowserCrawlResult result = new BrowserCrawler().crawl(requestFor("/").maxPages(2).build());

        assertThat(result.pages()).hasSize(2);
        assertThat(result.terminationReason())
                .isEqualTo(BrowserCrawlTerminationReason.MAX_PAGES_REACHED);
    }

    @Test
    void linkOutsideScopeIsRejectedNotNavigated() {
        BrowserCrawlResult result =
                new BrowserCrawler().crawl(requestFor("/external").maxDepth(1).build());

        assertThat(result.pages())
                .extracting(p -> p.finalUrl().toString())
                .containsExactly(baseUrl + "/external");
        assertThat(result.rejectedUrls())
                .anySatisfy(
                        link ->
                                assertThat(link.resolvedUrl().toString())
                                        .contains("out-of-scope.invalid"));
    }

    @Test
    void javaScriptInsertedLinkIsDiscoveredAfterStability() {
        BrowserCrawlResult result = new BrowserCrawler().crawl(requestFor("/dynamic").build());

        assertThat(result.pages())
                .extracting(p -> p.finalUrl().toString())
                .contains(baseUrl + "/dynamic", baseUrl + "/a");
    }

    @Test
    void cancellationStopsTheCrawlWithoutCompletingIt() {
        CancellationToken token = CancellationToken.create();
        token.cancel();

        BrowserCrawlResult result =
                new BrowserCrawler().crawl(requestFor("/").cancellationToken(token).build());

        assertThat(result.terminationReason()).isEqualTo(BrowserCrawlTerminationReason.CANCELLED);
        assertThat(result.pages()).isEmpty();
    }

    /**
     * Regression test for the concurrency correctness bug this branch fixes: the original Phase 0.7
     * engine navigated up to {@code maxConcurrency} pages at once via a worker-thread pool sharing
     * one {@code IPage} per thread, created from the same caller-supplied {@code IBrowser}. Neither
     * {@code IBrowser} nor {@code IPage} is documented as thread-safe, and the concrete Playwright
     * adapter backs both with unsynchronized state over one native Playwright browser/context -
     * under real concurrent navigation this silently corrupted the crawl (page {@code /b} vanished
     * from the committed result even though it was correctly discovered). The engine no longer
     * offers navigation concurrency at all: {@code maxConcurrency} must be exactly {@code 1}, and
     * every backend call happens on the single thread that calls {@code crawl(...)}. This test
     * asserts both halves of the fix: the invalid configuration is rejected before any navigation
     * starts, and a normal (single-lane) crawl of the exact graph that used to lose {@code /b}
     * still reaches every page.
     */
    @Test
    void maxConcurrencyAboveOneIsRejectedAndTheSequentialCrawlStillReachesEveryPage() {
        assertThatThrownBy(() -> requestFor("/").maxConcurrency(3).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exactly 1");

        BrowserCrawlResult result = new BrowserCrawler().crawl(requestFor("/").build());

        assertThat(result.pages())
                .extracting(p -> p.finalUrl().toString())
                .containsExactlyInAnyOrder(
                        baseUrl + "/", baseUrl + "/a", baseUrl + "/b", baseUrl + "/c");
        assertThat(result.terminationReason()).isEqualTo(BrowserCrawlTerminationReason.COMPLETED);
    }

    /**
     * BC-ROB-013 / stress regression for lost-or-duplicated URLs (section 13 of the concurrency
     * correction spec): a wider graph with several second-level branches, crawled three times
     * against the same deterministic local fixture, must reach every expected page exactly once
     * every time - no page ever disappears (the original bug) or gets navigated twice.
     */
    @Test
    void widerGraphIsCrawledCompletelyAndExactlyOnceAcrossRepeatedRuns() {
        for (int run = 0; run < 3; run++) {
            BrowserCrawlResult result =
                    new BrowserCrawler()
                            .crawl(requestFor("/wide").maxDepth(2).maxPages(20).build());

            assertThat(result.pages())
                    .extracting(p -> p.finalUrl().toString())
                    .containsExactlyInAnyOrder(
                            baseUrl + "/wide",
                            baseUrl + "/wide/1",
                            baseUrl + "/wide/2",
                            baseUrl + "/wide/3",
                            baseUrl + "/wide/4",
                            baseUrl + "/wide/5",
                            baseUrl + "/wide/6",
                            baseUrl + "/wide/1/child",
                            baseUrl + "/wide/2/child");
            assertThat(result.failures()).isEmpty();
            assertThat(result.terminationReason())
                    .isEqualTo(BrowserCrawlTerminationReason.COMPLETED);
        }
    }

    /**
     * Session semantics guard (section 17): one {@code IBrowser} is the crawl session, so cookies
     * set by the first crawled page must still be visible when the crawler navigates a linked page
     * that requires them. This protects against a future concurrency change accidentally splitting
     * navigation across independent browser instances/contexts, which would silently break session
     * continuity.
     */
    @Test
    void sessionCookieSetOnTheFirstPageIsStillPresentOnALinkedPage() {
        BrowserCrawlResult result =
                new BrowserCrawler().crawl(requestFor("/session-start").build());

        assertThat(result.failures()).isEmpty();
        BrowserCrawledPage sessionOnlyPage =
                result.pages().stream()
                        .filter(p -> p.finalUrl().toString().equals(baseUrl + "/session-only"))
                        .findFirst()
                        .orElseThrow(() -> new AssertionError("/session-only was never crawled"));
        // the page only renders this title when it receives the cookie /session-start set - proving
        // the crawl's two navigations shared one browser session/context, not independent ones
        assertThat(sessionOnlyPage.title()).contains("Authenticated");
    }

    /**
     * P0 regression: {@code navigationTimeout} must be the real, authoritative bound on a
     * navigation attempt - not merely a client-side check performed after a call a backend's own
     * (longer) default timeout already bounded. {@code /hang} takes 5s to respond and the
     * Playwright browser's own default navigation timeout is 30s; only a crawler-side timeout
     * threaded into the navigation call itself can stop this in ~1s.
     */
    @Test
    void navigationTimeoutShorterThanTheBrowserDefaultIsRespected() {
        long startNanos = System.nanoTime();

        BrowserCrawlResult result =
                new BrowserCrawler()
                        .crawl(
                                requestFor("/hang")
                                        .navigationTimeout(Duration.ofSeconds(1))
                                        .build());

        Duration elapsed = Duration.ofNanos(System.nanoTime() - startNanos);
        assertThat(result.pages()).isEmpty();
        assertThat(result.failures()).hasSize(1);
        assertThat(result.failures().get(0).type())
                .isEqualTo(BrowserCrawlFailureType.NAVIGATION_TIMEOUT);
        assertThat(elapsed).isLessThan(Duration.ofSeconds(4));
    }

    /**
     * P0 regression: an observation that hits the crawler's fixed capture bound must never be
     * recorded as a complete, successful discovery - a link past the retained boundary would
     * otherwise be silently missed rather than explicitly failed.
     */
    @Test
    void observationExceedingTheCaptureLimitBecomesAnExplicitFailureNotASilentPartialSuccess() {
        BrowserCrawlResult result = new BrowserCrawler().crawl(requestFor("/truncated").build());

        assertThat(result.pages()).isEmpty();
        assertThat(result.failures()).hasSize(1);
        assertThat(result.failures().get(0).type())
                .isEqualTo(BrowserCrawlFailureType.OBSERVATION_TRUNCATED);
        assertThat(result.rejectedUrls())
                .noneMatch(link -> link.resolvedUrl().toString().contains("/truncated/hidden"));
    }
}
