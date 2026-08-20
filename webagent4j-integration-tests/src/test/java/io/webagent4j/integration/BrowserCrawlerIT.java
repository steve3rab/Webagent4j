package io.webagent4j.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import io.webagent4j.browser.IBrowser;
import io.webagent4j.browsercrawler.BrowserCrawlRequest;
import io.webagent4j.browsercrawler.BrowserCrawlResult;
import io.webagent4j.browsercrawler.BrowserCrawlTerminationReason;
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

    @Test
    void boundedConcurrencyCompletesTheSameCrawlAsSequential() {
        BrowserCrawlResult result =
                new BrowserCrawler().crawl(requestFor("/").maxConcurrency(3).build());

        assertThat(result.pages())
                .extracting(p -> p.finalUrl().toString())
                .containsExactlyInAnyOrder(
                        baseUrl + "/", baseUrl + "/a", baseUrl + "/b", baseUrl + "/c");
        assertThat(result.terminationReason()).isEqualTo(BrowserCrawlTerminationReason.COMPLETED);
    }
}
