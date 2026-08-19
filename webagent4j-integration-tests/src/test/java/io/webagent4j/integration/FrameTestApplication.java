package io.webagent4j.integration;

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
 * Deterministic local HTTP fixture serving iframe-embedding host pages and their child documents
 * for the frame/iframe support integration tests.
 *
 * <p>A page whose mutation a test drives explicitly (removal, replacement, growing ambiguity before
 * calling {@code execute()}/{@code single()} again) exposes that mutation as a plain named
 * JavaScript function, invoked from the test with {@code page.evaluate("functionName()")} - never a
 * {@code setTimeout(...)} the test then has to out-wait with a blind delay - matching this suite's
 * documented fixture discipline (see docs/testing.md). Delayed insertion and the handful of tests
 * that specifically exercise an active {@code WaitEngine} poll racing a mutation (frame disappears
 * or becomes ambiguous mid-wait) are the only routes that legitimately still use {@code
 * setTimeout}: for those, exercising the race itself is the entire point of the test.
 */
final class FrameTestApplication implements AutoCloseable {

    private final HttpServer server;
    private final ExecutorService executor;
    private final String baseUrl;
    private final Map<String, AtomicInteger> namedClickCounts;

    private FrameTestApplication(
            HttpServer server,
            ExecutorService executor,
            Map<String, AtomicInteger> namedClickCounts) {
        this.server = server;
        this.executor = executor;
        this.namedClickCounts = namedClickCounts;
        this.baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    static FrameTestApplication start() throws IOException {
        return start(null);
    }

    /**
     * Starts a fixture server. When {@code crossOriginChildBaseUrl} is supplied, {@code
     * /frames/cross-origin-host} embeds its iframe from that other origin instead of this server -
     * used to prove cross-origin iframe traversal works without weakening browser security.
     */
    static FrameTestApplication start(String crossOriginChildBaseUrl) throws IOException {
        Map<String, AtomicInteger> namedCounts = new ConcurrentHashMap<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/count-click",
                exchange -> {
                    String path = exchange.getRequestURI().getPath();
                    String prefix = "/count-click";
                    String name =
                            path.length() > prefix.length() + 1
                                    ? path.substring(prefix.length() + 1)
                                    : "";
                    if (!name.isEmpty()) {
                        namedCounts
                                .computeIfAbsent(name, key -> new AtomicInteger())
                                .incrementAndGet();
                    }
                    respond(exchange, "text/plain; charset=utf-8", "ok");
                });
        server.createContext(
                "/frames",
                exchange -> {
                    String path = exchange.getRequestURI().getPath();
                    respond(
                            exchange,
                            "text/html; charset=utf-8",
                            page(path, crossOriginChildBaseUrl));
                });
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        server.start();
        return new FrameTestApplication(server, executor, namedCounts);
    }

    String url(String route) {
        return baseUrl + route;
    }

    int clickCount(String name) {
        AtomicInteger recorded = namedClickCounts.get(name);
        return recorded == null ? 0 : recorded.get();
    }

    @Override
    public void close() {
        server.stop(0);
        executor.close();
    }

    private static String page(String path, String crossOriginChildBaseUrl) {
        return switch (path) {
            case "/frames/simple" ->
                    host(
                            "Simple frame host",
                            iframe("checkout", "checkout", "/frames/child/checkout"));
            case "/frames/two-identical-payment" ->
                    host(
                            "Two identical payment frames",
                            iframe("payment-1", "payment", "/frames/child/payment-1")
                                    + iframe("payment-2", "payment", "/frames/child/payment-2"));
            case "/frames/wrong-frame-buy" ->
                    host(
                            "Two frames with identical buy buttons",
                            iframe("product-a-frame", "product-a", "/frames/child/buy-a")
                                    + iframe(
                                            "product-b-frame", "product-b", "/frames/child/buy-b"));
            case "/frames/nested" ->
                    host(
                            "Nested frame host",
                            iframe("outer-frame", "outer", "/frames/child/nested-outer"));
            case "/frames/no-target" ->
                    host(
                            "Frame with no target",
                            iframe("checkout-frame", "checkout", "/frames/child/empty"));
            case "/frames/no-iframe" -> host("No iframe present", "<p>No frames on this page.</p>");
            case "/frames/delayed-insert" ->
                    host(
                            "Delayed iframe insertion",
                            """
                            <p>Waiting for checkout...</p>
                            <script>setTimeout(() => {
                              const f = document.createElement('iframe');
                              f.name = 'checkout'; f.id = 'checkout-frame';
                              f.src = '/frames/child/checkout';
                              document.body.appendChild(f);
                            }, 150)</script>
                            """);
            case "/frames/disappearing-during-wait" ->
                    host(
                            "Frame disappears during wait",
                            iframe("checkout-frame", "checkout", "/frames/child/checkout")
                                    + """
                                    <script>setTimeout(() => {
                                      document.querySelector('iframe[name="checkout"]').remove();
                                    }, 150)</script>
                                    """);
            case "/frames/becomes-ambiguous-during-wait" ->
                    host(
                            "Frame becomes ambiguous during wait",
                            iframe("payment-frame", "payment", "/frames/child/payment-1")
                                    + """
                                    <script>setTimeout(() => {
                                      const duplicate = document.createElement('iframe');
                                      duplicate.name = 'payment'; duplicate.id = 'payment-frame-2';
                                      duplicate.src = '/frames/child/payment-2';
                                      document.body.appendChild(duplicate);
                                    }, 150)</script>
                                    """);
            case "/frames/replace-on-call" ->
                    host(
                            "Frame replaced on call",
                            iframe("checkout-frame", "checkout", "/frames/child/checkout")
                                    + """
                                    <script>
                                      function replaceCheckoutFrame() {
                                        const old = document.querySelector('iframe[name="checkout"]');
                                        const fresh = document.createElement('iframe');
                                        fresh.name = 'checkout'; fresh.id = 'checkout-frame-2';
                                        fresh.src = '/frames/child/checkout-v2';
                                        old.replaceWith(fresh);
                                      }
                                    </script>
                                    """);
            case "/frames/ambiguous-on-call" ->
                    host(
                            "Frame becomes ambiguous on call",
                            iframe("payment-frame", "payment", "/frames/child/payment-1")
                                    + """
                                    <script>
                                      function addSecondPaymentFrame() {
                                        const duplicate = document.createElement('iframe');
                                        duplicate.name = 'payment'; duplicate.id = 'payment-frame-2';
                                        duplicate.src = '/frames/child/payment-2';
                                        document.body.appendChild(duplicate);
                                      }
                                    </script>
                                    """);
            case "/frames/remove-on-call" ->
                    host(
                            "Frame removed on call",
                            iframe("checkout-frame", "checkout", "/frames/child/checkout")
                                    + """
                                    <script>
                                      function removeCheckoutFrame() {
                                        document.querySelector('iframe[name="checkout"]').remove();
                                      }
                                    </script>
                                    """);
            case "/frames/nav-a" ->
                    host(
                            "Frame navigation host",
                            iframe("nav-frame", "navtarget", "/frames/child/nav-a"));
            case "/frames/nested-nav" ->
                    host(
                            "Nested frame navigation host",
                            iframe("outer-frame", "outer", "/frames/child/nested-outer-nav"));
            case "/frames/cross-origin-host" ->
                    host(
                            "Cross-origin frame host",
                            iframe(
                                    "external-frame",
                                    "external",
                                    crossOriginChildBaseUrl + "/frames/child/cross-origin-target"));
            case "/frames/child/checkout" -> child("Checkout", "Pay", "checkout-pay");
            case "/frames/child/checkout-v2" -> child("Checkout v2", "Pay", "checkout-v2-pay");
            case "/frames/child/payment-1" -> child("Payment 1", "Pay", "payment-1-pay");
            case "/frames/child/payment-2" -> child("Payment 2", "Pay", "payment-2-pay");
            case "/frames/child/buy-a" -> child("Product A", "Buy", "product-a-buy");
            case "/frames/child/buy-b" -> child("Product B", "Buy", "product-b-buy");
            case "/frames/child/empty" -> document("Empty child", "<p>Nothing to click here.</p>");
            case "/frames/child/nested-outer" ->
                    host(
                            "Nested outer child",
                            iframe("inner-frame", "inner", "/frames/child/nested-inner"));
            case "/frames/child/nested-inner" -> child("Nested inner", "Pay", "nested-pay");
            case "/frames/child/nav-a" -> child("Nav page A", "Mark A", "nav-a-marker");
            case "/frames/child/nav-b" -> child("Nav page B", "Mark B", "nav-b-marker");
            case "/frames/child/nested-outer-nav" ->
                    host(
                            "Nested outer nav child",
                            iframe("inner-frame", "inner", "/frames/child/nested-nav-a"));
            case "/frames/child/nested-nav-a" ->
                    child("Nested nav page A", "Mark nested A", "nested-nav-a-marker");
            case "/frames/child/nested-nav-b" ->
                    child("Nested nav page B", "Mark nested B", "nested-nav-b-marker");
            case "/frames/child/cross-origin-target" ->
                    child("Cross-origin target", "Pay", "cross-origin-pay");
            default -> document("Not found", "<p>No fixture for " + path + "</p>");
        };
    }

    private static String iframe(String id, String name, String src) {
        return "<iframe id=\"" + id + "\" name=\"" + name + "\" src=\"" + src + "\"></iframe>";
    }

    private static String host(String title, String body) {
        return document(title, body);
    }

    private static String child(String title, String buttonLabel, String counterName) {
        return document(
                title,
                "<button onclick=\"fetch('/count-click/"
                        + counterName
                        + "').then(() => { status.textContent = 'Done'; })\">"
                        + buttonLabel
                        + "</button><p id=\"status\">Ready</p>");
    }

    private static String document(String title, String body) {
        return "<!doctype html><html lang=\"en\"><head><title>"
                + title
                + "</title></head><body><main><h1>"
                + title
                + "</h1>"
                + body
                + "</main></body></html>";
    }

    private static void respond(
            com.sun.net.httpserver.HttpExchange exchange, String contentType, String body)
            throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, payload.length);
        try (var output = exchange.getResponseBody()) {
            output.write(payload);
        }
    }
}
