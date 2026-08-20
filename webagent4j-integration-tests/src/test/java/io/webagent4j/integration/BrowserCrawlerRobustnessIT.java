package io.webagent4j.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import io.webagent4j.browser.IBrowser;
import io.webagent4j.browsercrawler.BrowserCrawlFailureType;
import io.webagent4j.browsercrawler.BrowserCrawlRequest;
import io.webagent4j.browsercrawler.BrowserCrawlResult;
import io.webagent4j.browsercrawler.BrowserCrawlTerminationReason;
import io.webagent4j.browsercrawler.BrowserCrawler;
import io.webagent4j.browsercrawler.CancellationToken;
import io.webagent4j.core.WebAgent;
import io.webagent4j.crawler.api.CrawlDecisionType;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Named, deterministic, real-Playwright adversarial scenarios (BC-ROB-001..016, STABILITY-002) for
 * {@link BrowserCrawler} - pathological and boundary-case graphs the engine must survive without
 * hanging, losing pages, exceeding a configured limit, or leaking a crawler-owned page, against a
 * local HTTP fixture only. Mirrors {@code HttpCrawlerRobustnessIT}'s naming and structure for the
 * sibling HTTP engine. Concurrency-specific scenarios (lost/duplicated URLs under real navigation
 * parallelism, reversed completion timing) are deliberately not included: this engine only supports
 * a single navigation lane (see {@code docs/browser-crawler.md#concurrency-model}), so there is no
 * physical concurrency left to race - {@link BrowserCrawlerIT}'s {@code
 * maxConcurrencyAboveOneIsRejectedAndTheSequentialCrawlStillReachesEveryPage} and {@code
 * widerGraphIsCrawledCompletelyAndExactlyOnceAcrossRepeatedRuns} cover that engine's actual
 * concurrency contract instead. BC-ROB-016 runs {@code crawl(...)} itself on this test method's own
 * thread throughout, matching the thread {@code browser} was launched on - only the {@code
 * cancel()} call is dispatched from a second thread ({@link CancellationToken} is a plain
 * thread-safe primitive, safe to touch from anywhere).
 *
 * <p>Every test carries a class-level {@link Timeout} so a genuine hang fails fast with a thread
 * dump captured at the deadline, rather than silently consuming this whole job's CI-level {@code
 * timeout-minutes} budget with zero diagnostic output - exactly what happened once already during
 * this phase's development (see the git history for the incident this guards against).
 */
@Timeout(value = 45, unit = TimeUnit.SECONDS)
class BrowserCrawlerRobustnessIT {

    private static HttpServer server;
    private static ExecutorService serverExecutor;
    private static String baseUrl;

    // BC-ROB-016 coordination only - see the /rob/cancelmidflight handler and the test itself.
    private static volatile CountDownLatch cancelMidFlightRequestStarted;
    private static volatile CountDownLatch cancelMidFlightAllowResponse;

    private IBrowser browser;

    @BeforeAll
    static void startFixture() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);

        // BC-ROB-001: a -> b -> c -> a
        server.createContext("/rob/cycle/a", exchange -> respond(exchange, html("A", link("b"))));
        server.createContext("/rob/cycle/b", exchange -> respond(exchange, html("B", link("c"))));
        server.createContext("/rob/cycle/c", exchange -> respond(exchange, html("C", link("a"))));

        // BC-ROB-002: fifty identical links to one target. Absolute hrefs deliberately - a bare
        // relative href resolved against a path with no trailing slash (like "/rob/dupfanout")
        // replaces its last segment rather than descending under it, per RFC 3986 5.3.
        server.createContext(
                "/rob/dupfanout",
                exchange ->
                        respond(
                                exchange,
                                html("Dup", "<a href=\"/rob/dupfanout/target\">T</a>".repeat(50))));
        server.createContext(
                "/rob/dupfanout/target", exchange -> respond(exchange, html("Target", "")));

        // BC-ROB-003: fragment and dot-segment variants of the same target (absolute hrefs, same
        // reasoning as BC-ROB-002 above).
        server.createContext(
                "/rob/norm",
                exchange ->
                        respond(
                                exchange,
                                html(
                                        "Norm",
                                        "<a href=\"/rob/norm/target\">T1</a>"
                                                + "<a href=\"/rob/norm/target#section\">T2</a>"
                                                + "<a href=\"/rob/norm/./x/../target\">T3</a>")));
        server.createContext(
                "/rob/norm/target", exchange -> respond(exchange, html("NormTarget", "")));

        // BC-ROB-004/005: a linear chain long enough to exercise both maxPages and maxDepth bounds
        server.createContext(
                "/rob/chain/0", exchange -> respond(exchange, html("Chain0", link("../chain/1"))));
        for (int i = 1; i <= 9; i++) {
            int current = i;
            int next = i + 1;
            server.createContext(
                    "/rob/chain/" + i,
                    exchange ->
                            respond(exchange, html("Chain" + current, link("../chain/" + next))));
        }

        // BC-ROB-007: a page linking to a host nothing listens on - a real navigation failure
        server.createContext(
                "/rob/backendfailure",
                exchange ->
                        respond(
                                exchange,
                                html(
                                        "BackendFailure",
                                        "<a href=\"http://127.0.0.1:1/\">Dead</a>")));

        // BC-ROB-008: DOM mutates forever - stability is never reached within the budget
        server.createContext(
                "/rob/neverstable",
                exchange ->
                        respond(
                                exchange,
                                """
                                <!doctype html>
                                <html><head><title>NeverStable</title></head>
                                <body>
                                <script>
                                  setInterval(function() {
                                    document.body.appendChild(document.createElement('span'));
                                  }, 50);
                                </script>
                                </body></html>
                                """));

        // BC-ROB-009/010: one link inserted quickly (well before the stability window elapses) and
        // a second inserted long afterward (well after that same crawl's observation is already
        // taken) - the first must be discovered, the second must not.
        server.createContext(
                "/rob/latemutation",
                exchange ->
                        respond(
                                exchange,
                                """
                                <!doctype html>
                                <html><head><title>LateMutation</title></head>
                                <body>
                                <script>
                                  setTimeout(function() {
                                    var early = document.createElement('a');
                                    early.href = '/rob/latemutation/early';
                                    early.textContent = 'Early';
                                    document.body.appendChild(early);
                                  }, 50);
                                  setTimeout(function() {
                                    var late = document.createElement('a');
                                    late.href = '/rob/latemutation/too-late';
                                    late.textContent = 'Too late';
                                    document.body.appendChild(late);
                                  }, 3000);
                                </script>
                                </body></html>
                                """));
        server.createContext(
                "/rob/latemutation/early", exchange -> respond(exchange, html("Early", "")));
        server.createContext(
                "/rob/latemutation/too-late", exchange -> respond(exchange, html("TooLate", "")));

        // BC-ROB-011: an out-of-scope external link, never navigated
        server.createContext(
                "/rob/outofscope",
                exchange ->
                        respond(
                                exchange,
                                html(
                                        "OutOfScope",
                                        "<a href=\"https://out-of-scope.invalid/never\">Never</a>")));

        // BC-ROB-012: navigation ends up on an out-of-scope final URL (client-side redirect). The
        // target is this same server reachable as "localhost" instead of "127.0.0.1" - a real,
        // locally resolvable navigation (no external DNS/network needed) whose host string is still
        // a different scope identity from the "127.0.0.1" seed, exactly like a real cross-host
        // redirect would be.
        server.createContext(
                "/rob/redirectsaway",
                exchange ->
                        respond(
                                exchange,
                                "<!doctype html><html><head><title>RedirectsAway</title>"
                                        + "<meta http-equiv=\"refresh\" content=\"0; url=http://localhost:"
                                        + server.getAddress().getPort()
                                        + "/rob/redirectsaway/landed\">"
                                        + "</head><body></body></html>"));
        server.createContext(
                "/rob/redirectsaway/landed", exchange -> respond(exchange, html("Landed", "")));

        // BC-ROB-006/013: resource cleanup - a small multi-page graph, and a failFast graph
        // (absolute href, same reasoning as BC-ROB-002 above)
        server.createContext(
                "/rob/cleanup/ok",
                exchange ->
                        respond(
                                exchange,
                                html("CleanupOk", "<a href=\"/rob/cleanup/ok/child\">C</a>")));
        server.createContext(
                "/rob/cleanup/ok/child", exchange -> respond(exchange, html("CleanupOkChild", "")));
        server.createContext(
                "/rob/cleanup/failfast",
                exchange ->
                        respond(
                                exchange,
                                html(
                                        "CleanupFailFast",
                                        "<a href=\"http://127.0.0.1:1/\">Dead</a>")));

        // BC-ROB-014: deterministic repeated runs (absolute hrefs, same reasoning as BC-ROB-002
        // above - "/rob/deterministic" has no trailing slash, so a bare relative "a"/"b" would
        // resolve as a sibling of "deterministic" under "/rob/", not underneath it).
        server.createContext(
                "/rob/deterministic",
                exchange ->
                        respond(
                                exchange,
                                html(
                                        "Det",
                                        "<a href=\"/rob/deterministic/a\">a</a>"
                                                + "<a href=\"/rob/deterministic/b\">b</a>")));
        server.createContext(
                "/rob/deterministic/a", exchange -> respond(exchange, html("DetA", "")));
        server.createContext(
                "/rob/deterministic/b", exchange -> respond(exchange, html("DetB", "")));

        // BC-ROB-015: an href-only attribute churn (element count constant throughout) settles
        // through three values before stopping - a count-only stability fingerprint cannot see the
        // churn at all and would declare stability at a fixed point regardless of it.
        server.createContext(
                "/rob/hrefmutation",
                exchange ->
                        respond(
                                exchange,
                                """
                                <!doctype html>
                                <html><head><title>HrefMutation</title></head>
                                <body>
                                <a id="l" href="/rob/hrefmutation/initial">L</a>
                                <script>
                                  var hrefs = ['/rob/hrefmutation/wrong1',
                                               '/rob/hrefmutation/wrong2',
                                               '/rob/hrefmutation/final'];
                                  var delays = [100, 150, 150];
                                  var link = document.getElementById('l');
                                  function schedule(idx) {
                                    if (idx >= hrefs.length) { return; }
                                    setTimeout(function() {
                                      link.setAttribute('href', hrefs[idx]);
                                      schedule(idx + 1);
                                    }, delays[idx]);
                                  }
                                  schedule(0);
                                </script>
                                </body></html>
                                """));
        server.createContext(
                "/rob/hrefmutation/initial", exchange -> respond(exchange, html("Initial", "")));
        server.createContext(
                "/rob/hrefmutation/wrong1", exchange -> respond(exchange, html("Wrong1", "")));
        server.createContext(
                "/rob/hrefmutation/wrong2", exchange -> respond(exchange, html("Wrong2", "")));

        // STABILITY-002: two anchors' href values are redistributed across a literal "|" character
        // such that a naive "|"-delimited-join fingerprint would see the identical combined string
        // before and after ("a|b" + "c" joins the same as "a" + "b|c"), even though the actual
        // hrefs are genuinely different. maxDepth(0) below means these are only ever discovered,
        // never navigated, so no server route is needed for either value.
        server.createContext(
                "/rob/pipehref",
                exchange ->
                        respond(
                                exchange,
                                """
                                <!doctype html>
                                <html><head><title>PipeHref</title></head>
                                <body>
                                <a id="l1" href="a|b">L1</a>
                                <a id="l2" href="c">L2</a>
                                <script>
                                  setTimeout(function() {
                                    document.getElementById('l1').setAttribute('href', 'a');
                                    document.getElementById('l2').setAttribute('href', 'b|c');
                                  }, 100);
                                </script>
                                </body></html>
                                """));
        server.createContext(
                "/rob/hrefmutation/final", exchange -> respond(exchange, html("Final", "")));

        // BC-ROB-016: cancellation observed while a navigation is genuinely in flight. The handler
        // signals cancelMidFlightRequestStarted the instant the request arrives, then blocks on
        // cancelMidFlightAllowResponse - the test releases that latch only after cancel() has
        // already been called, so the response (and the page's three children) can never arrive
        // before cancellation is observed. No wall-clock guessing on either side.
        server.createContext(
                "/rob/cancelmidflight",
                exchange -> {
                    CountDownLatch started = cancelMidFlightRequestStarted;
                    CountDownLatch allowResponse = cancelMidFlightAllowResponse;
                    if (started != null) {
                        started.countDown();
                    }
                    if (allowResponse != null) {
                        try {
                            allowResponse.await();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                    }
                    respond(
                            exchange,
                            html(
                                    "CancelMidFlight",
                                    "<a href=\"/rob/cancelmidflight/x\">X</a>"
                                            + "<a href=\"/rob/cancelmidflight/y\">Y</a>"
                                            + "<a href=\"/rob/cancelmidflight/z\">Z</a>"));
                });
        server.createContext(
                "/rob/cancelmidflight/x", exchange -> respond(exchange, html("X", "")));
        server.createContext(
                "/rob/cancelmidflight/y", exchange -> respond(exchange, html("Y", "")));
        server.createContext(
                "/rob/cancelmidflight/z", exchange -> respond(exchange, html("Z", "")));

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

    private static String link(String relativeHref) {
        return "<a href=\"" + relativeHref + "\">" + relativeHref + "</a>";
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
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
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

    // BC-ROB-001: a cyclic graph terminates rather than looping forever; each identity is crawled
    // exactly once.
    @Test
    void bcRob001CyclicGraphTerminatesWithoutAnInfiniteLoop() {
        BrowserCrawlResult result =
                new BrowserCrawler().crawl(requestFor("/rob/cycle/a").maxDepth(5).build());

        assertThat(result.pages())
                .extracting(p -> p.finalUrl().toString())
                .containsExactlyInAnyOrder(
                        baseUrl + "/rob/cycle/a",
                        baseUrl + "/rob/cycle/b",
                        baseUrl + "/rob/cycle/c");
        assertThat(result.statistics().duplicateUrls()).isEqualTo(1);
        assertThat(result.terminationReason()).isEqualTo(BrowserCrawlTerminationReason.COMPLETED);
    }

    // BC-ROB-002: fifty identical links to the same target claim exactly one navigation.
    @Test
    void bcRob002DuplicateFanOutClaimsOnlyOneNavigation() {
        BrowserCrawlResult result =
                new BrowserCrawler().crawl(requestFor("/rob/dupfanout").maxDepth(1).build());

        assertThat(result.pages()).hasSize(2);
        assertThat(result.statistics().duplicateUrls()).isEqualTo(49);
    }

    // BC-ROB-003: a fragment and a dot-segment variant of the same target both dedup to one
    // identity.
    @Test
    void bcRob003NormalizationVariantsDedupToOneIdentity() {
        BrowserCrawlResult result =
                new BrowserCrawler().crawl(requestFor("/rob/norm").maxDepth(1).build());

        assertThat(result.pages()).hasSize(2);
        assertThat(result.statistics().duplicateUrls()).isEqualTo(2);
    }

    // BC-ROB-004: maxPages is an exact bound, never exceeded, even though the graph is larger.
    @Test
    void bcRob004MaxPagesIsNeverExceeded() {
        BrowserCrawlResult result =
                new BrowserCrawler()
                        .crawl(requestFor("/rob/chain/0").maxDepth(20).maxPages(4).build());

        assertThat(result.pages()).hasSize(4);
        assertThat(result.terminationReason())
                .isEqualTo(BrowserCrawlTerminationReason.MAX_PAGES_REACHED);
    }

    // BC-ROB-005: maxDepth is an exact bound - nothing beyond it is ever navigated.
    @Test
    void bcRob005MaxDepthIsNeverExceeded() {
        BrowserCrawlResult result =
                new BrowserCrawler()
                        .crawl(requestFor("/rob/chain/0").maxDepth(2).maxPages(50).build());

        assertThat(result.pages()).hasSize(3);
        assertThat(result.pages()).extracting(p -> p.depth()).containsExactly(0, 1, 2);
        assertThat(result.rejectedUrls())
                .anySatisfy(
                        link ->
                                assertThat(link.rejection().map(d -> d.type()).orElseThrow())
                                        .isEqualTo(CrawlDecisionType.REJECT_DEPTH));
    }

    // BC-ROB-006: cancellation observed before any navigation starts leaves no leaked page open on
    // the caller's browser.
    @Test
    void bcRob006CancellationLeaksNoCrawlerOwnedPage() {
        CancellationToken token = CancellationToken.create();
        token.cancel();

        BrowserCrawlResult result =
                new BrowserCrawler()
                        .crawl(requestFor("/rob/cleanup/ok").cancellationToken(token).build());

        assertThat(result.terminationReason()).isEqualTo(BrowserCrawlTerminationReason.CANCELLED);
        assertThat(browser.pages()).isEmpty();
    }

    // BC-ROB-007: an unreachable backend target is a real, explicit navigation failure - never a
    // silently empty success.
    @Test
    void bcRob007UnreachableBackendIsAnExplicitFailureNotASilentEmptySuccess() {
        BrowserCrawlResult result =
                new BrowserCrawler().crawl(requestFor("/rob/backendfailure").build());

        assertThat(result.pages())
                .extracting(p -> p.finalUrl().toString())
                .containsExactly(baseUrl + "/rob/backendfailure");
        assertThat(result.failures()).hasSize(1);
        assertThat(result.failures().get(0).type())
                .isIn(
                        BrowserCrawlFailureType.NAVIGATION_FAILED,
                        BrowserCrawlFailureType.NAVIGATION_TIMEOUT);
    }

    // BC-ROB-008: a DOM that mutates forever never reaches stability - a bounded failure with the
    // correct type, not a hang.
    @Test
    void bcRob008DomThatNeverStabilizesFailsWithTheCorrectType() {
        BrowserCrawlResult result =
                new BrowserCrawler()
                        .crawl(
                                requestFor("/rob/neverstable")
                                        .navigationTimeout(Duration.ofSeconds(2))
                                        .stabilityWindow(Duration.ofSeconds(1))
                                        .build());

        assertThat(result.pages()).isEmpty();
        assertThat(result.failures()).hasSize(1);
        assertThat(result.failures().get(0).type())
                .isEqualTo(BrowserCrawlFailureType.PAGE_STABILITY_TIMEOUT);
    }

    // BC-ROB-009: a link inserted before stability completes (here, at 50ms) is part of that same
    // observation.
    @Test
    void bcRob009LinkInsertedBeforeStabilityIsDiscovered() {
        BrowserCrawlResult result =
                new BrowserCrawler()
                        .crawl(
                                requestFor("/rob/latemutation")
                                        .maxDepth(1)
                                        .stabilityWindow(Duration.ofMillis(200))
                                        .navigationTimeout(Duration.ofSeconds(10))
                                        .build());

        assertThat(result.pages())
                .extracting(p -> p.finalUrl().toString())
                .contains(baseUrl + "/rob/latemutation", baseUrl + "/rob/latemutation/early");
    }

    // BC-ROB-010: a link inserted long after the crawl's observation was already taken (here, at
    // 3s, well past this 200ms stability window) is never discovered - the engine takes one
    // snapshot per navigation, it never keeps monitoring the DOM afterward.
    @Test
    void bcRob010LinkInsertedAfterStabilityWasAlreadyAcceptedIsNeverDiscovered() {
        BrowserCrawlResult result =
                new BrowserCrawler()
                        .crawl(
                                requestFor("/rob/latemutation")
                                        .stabilityWindow(Duration.ofMillis(200))
                                        .navigationTimeout(Duration.ofSeconds(10))
                                        .build());

        assertThat(result.pages())
                .extracting(p -> p.finalUrl().toString())
                .doesNotContain(baseUrl + "/rob/latemutation/too-late");
        assertThat(result.rejectedUrls())
                .noneMatch(link -> link.resolvedUrl().toString().contains("too-late"));
    }

    // BC-ROB-011: an out-of-scope link is discovered but never navigated.
    @Test
    void bcRob011OutOfScopeLinkIsNeverNavigated() {
        BrowserCrawlResult result =
                new BrowserCrawler().crawl(requestFor("/rob/outofscope").maxDepth(1).build());

        assertThat(result.pages()).hasSize(1);
        assertThat(result.rejectedUrls())
                .anySatisfy(
                        link ->
                                assertThat(link.resolvedUrl().toString())
                                        .contains("out-of-scope.invalid"));
    }

    // BC-ROB-012: a navigation whose final URL leaves scope (client-side redirect) fails closed
    // rather than being silently indexed. The redirect can land squarely between two stability
    // polls (a clean OUT_OF_SCOPE_REDIRECT) or destroy the execution context a poll's evaluate()
    // is mid-flight in (a real Playwright condition, classified BROWSER_BACKEND_FAILURE) -
    // whichever it is, the page must never be silently indexed as a success.
    @Test
    void bcRob012OutOfScopeFinalUrlFailsClosed() {
        BrowserCrawlResult result =
                new BrowserCrawler().crawl(requestFor("/rob/redirectsaway").build());

        assertThat(result.pages()).isEmpty();
        assertThat(result.failures()).hasSize(1);
        assertThat(result.failures().get(0).type())
                .isIn(
                        BrowserCrawlFailureType.OUT_OF_SCOPE_REDIRECT,
                        BrowserCrawlFailureType.BROWSER_BACKEND_FAILURE);
    }

    // BC-ROB-013: every crawler-created page is closed - under a normal successful multi-page
    // crawl and under failFast alike.
    @Test
    void bcRob013CrawlerOwnedPagesAreAlwaysClosed() {
        new BrowserCrawler().crawl(requestFor("/rob/cleanup/ok").build());
        assertThat(browser.pages()).isEmpty();

        new BrowserCrawler().crawl(requestFor("/rob/cleanup/failfast").failFast(true).build());
        assertThat(browser.pages()).isEmpty();
    }

    // BC-ROB-014: the same deterministic fixture, crawled repeatedly, produces structurally
    // equivalent results every time (duration/cause identity excluded, per the documented
    // determinism contract).
    @Test
    void bcRob014RepeatedRunsProduceStructurallyEquivalentResults() {
        for (int run = 0; run < 3; run++) {
            BrowserCrawlResult result =
                    new BrowserCrawler().crawl(requestFor("/rob/deterministic").build());

            assertThat(result.pages())
                    .extracting(p -> p.finalUrl().toString(), p -> p.depth())
                    .containsExactly(
                            org.assertj.core.groups.Tuple.tuple(baseUrl + "/rob/deterministic", 0),
                            org.assertj.core.groups.Tuple.tuple(
                                    baseUrl + "/rob/deterministic/a", 1),
                            org.assertj.core.groups.Tuple.tuple(
                                    baseUrl + "/rob/deterministic/b", 1));
            assertThat(result.failures()).isEmpty();
            assertThat(result.terminationReason())
                    .isEqualTo(BrowserCrawlTerminationReason.COMPLETED);
        }
    }

    // BC-ROB-015: an href-only mutation sequence (element count constant throughout) is not
    // considered stable until the href itself stops changing. A count-only fingerprint would
    // declare stability at a fixed point (page load + stabilityWindow) regardless of the churn and
    // could capture a transient, non-final href instead of the settled one.
    @Test
    void bcRob015HrefOnlyMutationIsNotConsideredStableUntilItSettles() {
        BrowserCrawlResult result =
                new BrowserCrawler()
                        .crawl(
                                requestFor("/rob/hrefmutation")
                                        .maxDepth(1)
                                        .stabilityWindow(Duration.ofMillis(300))
                                        .navigationTimeout(Duration.ofSeconds(5))
                                        .build());

        assertThat(result.pages())
                .extracting(p -> p.finalUrl().toString())
                .contains(baseUrl + "/rob/hrefmutation", baseUrl + "/rob/hrefmutation/final");
        assertThat(result.pages())
                .extracting(p -> p.finalUrl().toString())
                .noneMatch(url -> url.contains("wrong1") || url.contains("wrong2"));
    }

    // STABILITY-002: a "|"-containing href does not collide with a differently-partitioned "|"
    // sequence from a sibling anchor under the JSON-encoded fingerprint - see the fixture above for
    // the exact collision this would trigger under a naive delimiter-joined fingerprint.
    @Test
    void stability002HrefContainingPipeCharacterDoesNotCreateAmbiguousFingerprintEquivalence() {
        BrowserCrawlResult result =
                new BrowserCrawler()
                        .crawl(
                                requestFor("/rob/pipehref")
                                        .maxDepth(0)
                                        .stabilityWindow(Duration.ofMillis(300))
                                        .navigationTimeout(Duration.ofSeconds(5))
                                        .build());

        assertThat(result.pages()).hasSize(1);
        assertThat(result.pages().get(0).links())
                .extracting(link -> link.rawHref())
                .containsExactlyInAnyOrder("a", "b|c");
    }

    // BC-ROB-016: cancellation observed while a navigation is genuinely in flight (from another
    // thread, mid-navigate()) still lets that navigation complete and its own page be recorded, but
    // the children it discovers are rejected rather than claimed, and no further navigation occurs.
    // The crawl itself runs on this test method's own thread throughout - only the cancel() call
    // is dispatched from a second thread (CancellationToken is a plain thread-safe AtomicBoolean,
    // safe to touch from anywhere) - the browser/page themselves are only ever touched from the one
    // thread that launched them, exactly as the single-execution-lane architecture requires.
    //
    // Deterministic by construction, not by timing: the /rob/cancelmidflight handler signals
    // cancelMidFlightRequestStarted the instant it receives the request, then blocks on
    // cancelMidFlightAllowResponse. This thread waits on the first latch (so it only proceeds once
    // the navigation has genuinely begun), calls cancel(), and only then releases the second latch
    // -
    // so the response, and therefore the page's three children, can never arrive before
    // cancellation
    // is observed. No Thread.sleep anywhere in this coordination.
    @Test
    void
            bcRob016CancellationDuringAnInFlightNavigationPreventsDiscoveredChildrenFromBeingClaimed() {
        CancellationToken token = CancellationToken.create();
        CountDownLatch requestStarted = new CountDownLatch(1);
        CountDownLatch allowResponse = new CountDownLatch(1);
        cancelMidFlightRequestStarted = requestStarted;
        cancelMidFlightAllowResponse = allowResponse;
        ExecutorService cancelExecutor = Executors.newSingleThreadExecutor();
        try {
            cancelExecutor.submit(
                    () -> {
                        try {
                            requestStarted.await();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                        token.cancel();
                        allowResponse.countDown();
                    });

            BrowserCrawlResult result =
                    new BrowserCrawler()
                            .crawl(
                                    requestFor("/rob/cancelmidflight")
                                            .cancellationToken(token)
                                            .build());

            assertThat(result.terminationReason())
                    .isEqualTo(BrowserCrawlTerminationReason.CANCELLED);
            assertThat(result.pages())
                    .extracting(p -> p.finalUrl().toString())
                    .containsExactly(baseUrl + "/rob/cancelmidflight");
            assertThat(result.statistics().claimedNavigations()).isEqualTo(1);
            assertThat(result.rejectedUrls()).hasSize(3);
            assertThat(result.rejectedUrls())
                    .allSatisfy(
                            link ->
                                    assertThat(link.rejection().map(d -> d.type()).orElseThrow())
                                            .isEqualTo(CrawlDecisionType.REJECT_CANCELLED));
        } finally {
            cancelExecutor.shutdownNow();
            cancelMidFlightRequestStarted = null;
            cancelMidFlightAllowResponse = null;
        }
    }
}
