package io.webagent4j.crawler.it;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Deterministic local HTTP fixture for {@link HttpCrawlerIT} and {@link HttpCrawlerRobustnessIT} -
 * every route is a plain, fixed response (or a small counter-driven sequence for the retry
 * scenario), so a crawl against it always produces the same result. No external network, no
 * browser: a bare {@link HttpServer} bound to an ephemeral loopback port.
 */
public final class HttpCrawlerTestServer implements AutoCloseable {

    private final HttpServer server;
    private final ExecutorService executor;
    private final String baseUrl;
    private final Map<String, AtomicInteger> callCounts = new ConcurrentHashMap<>();

    private HttpCrawlerTestServer(HttpServer server, ExecutorService executor) {
        this.server = server;
        this.executor = executor;
        this.baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** Starts the fixture on an ephemeral loopback port. */
    public static HttpCrawlerTestServer start() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        HttpCrawlerTestServer app = new HttpCrawlerTestServer(server, executor);
        server.createContext("/", app::dispatch);
        server.start();
        return app;
    }

    /** Returns the absolute URL for {@code route} on this fixture's base URL. */
    public String url(String route) {
        return baseUrl + route;
    }

    /** Returns how many times {@code path} has been requested so far. */
    public int callCount(String path) {
        AtomicInteger counter = callCounts.get(path);
        return counter == null ? 0 : counter.get();
    }

    @Override
    public void close() {
        server.stop(0);
        executor.close();
    }

    private void dispatch(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        callCounts.computeIfAbsent(path, key -> new AtomicInteger()).incrementAndGet();
        try {
            route(exchange, path);
        } finally {
            exchange.close();
        }
    }

    private void route(HttpExchange exchange, String path) throws IOException {
        switch (path) {
            // HTTP-001: seed page linking to two same-host pages.
            case "/h1/" ->
                    html(
                            exchange,
                            page(
                                    "<a href=\"/h1/about\">About</a><a href=\"/h1/products\">Products</a>"));
            case "/h1/about", "/h1/products" -> html(exchange, page("<p>ok</p>"));

            // HTTP-002: relative / root-relative / same-directory resolution.
            case "/h2/a/index.html" ->
                    html(
                            exchange,
                            page(
                                    "<a href=\"../b\">B</a><a href=\"./c\">C</a>"
                                            + "<a href=\"/root\">Root</a>"));
            case "/h2/b", "/h2/a/c", "/root" -> html(exchange, page("<p>ok</p>"));

            // HTTP-003: fragment-only variants must dedup to one fetch.
            case "/h3/seed" ->
                    html(
                            exchange,
                            page(
                                    "<a href=\"/h3/page\">P</a>"
                                            + "<a href=\"/h3/page#top\">Top</a>"
                                            + "<a href=\"/h3/page#bottom\">Bottom</a>"));
            case "/h3/page" -> html(exchange, page("<p>ok</p>"));

            // HTTP-004: dot-segment variant must dedup with the clean URL.
            case "/h4/seed" ->
                    html(
                            exchange,
                            page(
                                    "<a href=\"/h4/a/../products\">Dots</a>"
                                            + "<a href=\"/h4/products\">Clean</a>"));
            case "/h4/products" -> html(exchange, page("<p>ok</p>"));

            // HTTP-005: an external-host link must never be fetched under sameHostOnly.
            case "/h5/seed" ->
                    html(
                            exchange,
                            page(
                                    "<a href=\"/h5/local\">Local</a>"
                                            + "<a href=\"http://external.test/page\">External</a>"));
            case "/h5/local" -> html(exchange, page("<p>ok</p>"));

            // HTTP-006: a depth-0..3 chain, to be truncated by maxDepth.
            case "/h6/d0" -> html(exchange, page("<a href=\"/h6/d1\">D1</a>"));
            case "/h6/d1" -> html(exchange, page("<a href=\"/h6/d2\">D2</a>"));
            case "/h6/d2" -> html(exchange, page("<a href=\"/h6/d3\">D3</a>"));
            case "/h6/d3" -> html(exchange, page("<p>ok</p>"));

            // HTTP-007: five links, to be truncated by maxPages.
            case "/h7/seed" ->
                    html(
                            exchange,
                            page(
                                    "<a href=\"/h7/p1\">1</a><a href=\"/h7/p2\">2</a>"
                                            + "<a href=\"/h7/p3\">3</a><a href=\"/h7/p4\">4</a>"
                                            + "<a href=\"/h7/p5\">5</a>"));
            case "/h7/p1", "/h7/p2", "/h7/p3", "/h7/p4", "/h7/p5" ->
                    html(exchange, page("<p>ok</p>"));

            // HTTP-008: a two-hop redirect chain ending in success.
            case "/h8/a" -> redirect(exchange, 301, "/h8/b");
            case "/h8/b" -> redirect(exchange, 302, "/h8/final");
            case "/h8/final" -> html(exchange, page("<p>ok</p>"));

            // HTTP-009: a redirect loop.
            case "/h9/a" -> redirect(exchange, 302, "/h9/b");
            case "/h9/b" -> redirect(exchange, 302, "/h9/a");

            // HTTP-010: a terminal 404.
            case "/h10/missing" ->
                    respond(
                            exchange,
                            404,
                            "text/plain",
                            "not found".getBytes(StandardCharsets.UTF_8));

            // HTTP-011: fails once with 500, then succeeds - exercises bounded retry.
            case "/h11/flaky" -> {
                if (callCount(path) <= 1) {
                    respond(exchange, 500, "text/plain", "boom".getBytes(StandardCharsets.UTF_8));
                } else {
                    html(exchange, page("<p>recovered</p>"));
                }
            }

            // HTTP-012: responds slower than the caller's configured request timeout.
            case "/h12/slow" -> {
                try {
                    Thread.sleep(400);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
                html(exchange, page("<p>slow</p>"));
            }

            // HTTP-013: a body far larger than the caller's configured byte limit.
            case "/h13/huge" -> {
                byte[] body = new byte[20_000];
                java.util.Arrays.fill(body, (byte) 'a');
                respond(exchange, 200, "text/html", body);
            }

            // HTTP-014: a non-HTML content type that must never be parsed as a page.
            case "/h14/image.png" -> respond(exchange, 200, "image/png", new byte[] {1, 2, 3, 4});

            // HTTP-015: real UTF-8 accented text and a non-breaking space, not HTML entities.
            case "/h15/unicode" -> html(exchange, page("<p>Caf\u00e9\u00a0M\u00fcnchen</p>"));

            // HTTP-016: relative href resolved against a <base href>.
            case "/h16/page" ->
                    html(
                            exchange,
                            "<!doctype html><html><head><base href=\"/h16/catalog/\"></head>"
                                    + "<body><a href=\"item\">Item</a></body></html>");

            // HTTP-017: a relative declared canonical URL, declared in <head> as in real pages.
            case "/h17/page" ->
                    html(
                            exchange,
                            "<!doctype html><html><head>"
                                    + "<link rel=\"canonical\" href=\"canonical-path\"></head>"
                                    + "<body><p>ok</p></body></html>");

            // HTTP-018: the same target reached plain and with a tracking query parameter.
            case "/h18/seed" ->
                    html(
                            exchange,
                            page(
                                    "<a href=\"/h18/target\">Plain</a>"
                                            + "<a href=\"/h18/target?utm_source=newsletter\">Tracked</a>"));
            case "/h18/target" -> html(exchange, page("<p>ok</p>"));

            // HTTP-019: mailto/javascript links must never enter the frontier.
            case "/h19/seed" ->
                    html(
                            exchange,
                            page(
                                    "<a href=\"mailto:test@example.test\">Mail</a>"
                                            + "<a href=\"javascript:void(0)\">JS</a>"
                                            + "<a href=\"/h19/ok\">Ok</a>"));
            case "/h19/ok" -> html(exchange, page("<p>ok</p>"));

            // HTTP-020: malformed markup (unclosed tags, uppercase, unquoted href).
            case "/h20/malformed" ->
                    html(exchange, "<HTML><BODY><a href=/h20/ok>Ok<a href=\"/h20/second\">Second");
            case "/h20/ok", "/h20/second" -> html(exchange, page("<p>ok</p>"));

            // CRAWL-001: a cyclic graph A -> B -> C -> A must not loop forever.
            case "/c1/a" -> html(exchange, page("<a href=\"/c1/b\">B</a>"));
            case "/c1/b" -> html(exchange, page("<a href=\"/c1/c\">C</a>"));
            case "/c1/c" -> html(exchange, page("<a href=\"/c1/a\">A</a>"));

            // CRAWL-002: the same link repeated a hundred times fetches its target once.
            case "/c2/seed" -> html(exchange, page(repeatLink("/c2/self", 100)));
            case "/c2/self" -> html(exchange, page("<p>ok</p>"));

            // CRAWL-003: fragment, dot-segment, and scheme-case variants dedup to one identity.
            case "/c3/seed" ->
                    html(
                            exchange,
                            page(
                                    "<a href=\"/c3/target\">Plain</a>"
                                            + "<a href=\"/c3/target#frag\">Fragment</a>"
                                            + "<a href=\"/c3/x/../target\">DotSegment</a>"
                                            + ("<a href=\""
                                                    + schemeUppercased(exchange)
                                                    + "/c3/target\">SchemeCase</a>")));
            case "/c3/target" -> html(exchange, page("<p>ok</p>"));

            // CRAWL-004: a redirect to an external host must never be followed under sameHostOnly.
            case "/c4/seed" -> redirect(exchange, 302, "http://external.test/x");

            // CRAWL-005: thousands of duplicate links on one page must not explode the frontier.
            case "/c5/seed" -> html(exchange, page(repeatLink("/c5/target", 3_000)));
            case "/c5/target" -> html(exchange, page("<p>ok</p>"));

            // CRAWL-006: an unparsable href must not crash extraction of the rest of the page.
            case "/c6/seed" ->
                    html(
                            exchange,
                            page(
                                    "<a href=\"/c6/ok\">Ok</a>"
                                            + "<a href=\"http://[bad\">Bad</a>"
                                            + "<a href=\"/c6/ok2\">Ok2</a>"));
            case "/c6/ok", "/c6/ok2" -> html(exchange, page("<p>ok</p>"));

            // CRAWL-007: a very large body, well beyond the configured limit, is rejected quickly.
            case "/c7/huge" -> {
                byte[] body = new byte[5_000_000];
                java.util.Arrays.fill(body, (byte) 'a');
                respond(exchange, 200, "text/html", body);
            }

            // CRAWL-008: a redirect chain of exactly two hops, to be bounded by maxRedirects.
            case "/c8/r0" -> redirect(exchange, 302, "/c8/r1");
            case "/c8/r1" -> redirect(exchange, 302, "/c8/r2");
            case "/c8/r2" -> html(exchange, page("<p>ok</p>"));

            // CRAWL-009: the connection is closed with no response at all - a real transport
            // failure.
            case "/c9/reset" -> {
                // Intentionally empty: dispatch()'s finally block closes the exchange without ever
                // calling sendResponseHeaders(), which the client observes as a connection failure.
            }

            // CRAWL-011: two independently discovered seeds redirect to the same final URL - it
            // must be fetched only once, globally, across the whole crawl.
            case "/c11/a" -> redirect(exchange, 302, "/c11/final");
            case "/c11/b" -> redirect(exchange, 302, "/c11/final");
            case "/c11/final" -> html(exchange, page("<p>ok</p>"));

            // CRAWL-012: maxPages must bound a redirect hop exactly as it bounds any other fetch.
            case "/c12/a" -> redirect(exchange, 302, "/c12/b");
            case "/c12/b" -> html(exchange, page("<p>ok</p>"));

            // CRAWL-013: a redirect loop hidden behind a fragment and a dot-segment variant of the
            // same normalized identity must still be detected as a loop.
            case "/c13/a" -> redirect(exchange, 302, "/c13/b#one");
            case "/c13/b" -> redirect(exchange, 302, "/c13/a/../a#two");

            // CRAWL-014: a failure two redirect hops deep must report the real requested URL, the
            // real failing URL, and the full chain of hops actually followed.
            case "/c14/a" -> redirect(exchange, 302, "/c14/b");
            case "/c14/b" -> redirect(exchange, 302, "/c14/c");
            case "/c14/c" ->
                    respond(exchange, 500, "text/plain", "boom".getBytes(StandardCharsets.UTF_8));

            // Section 72 end-to-end scenario.
            case "/e2e/" ->
                    html(
                            exchange,
                            page(
                                    "<a href=\"/e2e/products\">Products</a>"
                                            + "<a href=\"/e2e/about\">About</a>"
                                            + "<a href=\"/e2e/products#featured\">Featured</a>"
                                            + "<a href=\"http://external.test/page\">External</a>"
                                            + "<a href=\"mailto:test@example.test\">Mail</a>"));
            case "/e2e/products" ->
                    html(
                            exchange,
                            page(
                                    "<a href=\"/e2e/products/1\">One</a>"
                                            + "<a href=\"/e2e/products/2\">Two</a>"
                                            + "<a href=\"/e2e/\">Home</a>"));
            case "/e2e/about" -> redirect(exchange, 302, "/e2e/company");
            case "/e2e/company" -> html(exchange, page("<p>ok</p>"));
            case "/e2e/products/1" -> html(exchange, page("<p>ok</p>"));
            case "/e2e/products/2" ->
                    respond(exchange, 500, "text/plain", "boom".getBytes(StandardCharsets.UTF_8));

            default ->
                    respond(
                            exchange,
                            404,
                            "text/plain",
                            "no route".getBytes(StandardCharsets.UTF_8));
        }
    }

    private static String repeatLink(String path, int count) {
        StringBuilder links = new StringBuilder();
        for (int i = 0; i < count; i++) {
            links.append("<a href=\"").append(path).append("\">L</a>");
        }
        return links.toString();
    }

    private static String schemeUppercased(HttpExchange exchange) {
        return "HTTP://" + exchange.getRequestHeaders().getFirst("Host");
    }

    private static String page(String bodyHtml) {
        return "<!doctype html><html lang=\"en\"><head><title>Crawler fixture</title></head>"
                + "<body>"
                + bodyHtml
                + "</body></html>";
    }

    private static void html(HttpExchange exchange, String body) throws IOException {
        respond(exchange, 200, "text/html; charset=utf-8", body.getBytes(StandardCharsets.UTF_8));
    }

    private static void redirect(HttpExchange exchange, int status, String location)
            throws IOException {
        exchange.getResponseHeaders().add("Location", location);
        exchange.sendResponseHeaders(status, -1);
    }

    private static void respond(HttpExchange exchange, int status, String contentType, byte[] body)
            throws IOException {
        if (!contentType.isEmpty()) {
            exchange.getResponseHeaders().add("Content-Type", contentType);
        }
        exchange.sendResponseHeaders(status, body.length);
        try (var output = exchange.getResponseBody()) {
            output.write(body);
        }
    }
}
