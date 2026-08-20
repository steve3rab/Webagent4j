package io.webagent4j.integration;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Deterministic local HTTP fixture serving pages for the extraction engine's integration tests.
 *
 * <p>Any page whose mutation a test drives explicitly (a form value changing, a frame being
 * replaced) exposes that mutation as a plain named JavaScript function invoked from the test with
 * {@code page.evaluate("functionName()")} - never a blind {@code setTimeout(...)} the test has to
 * out-wait - matching this suite's documented fixture discipline (see docs/testing.md).
 */
final class ExtractionTestApplication implements AutoCloseable {

    private final HttpServer server;
    private final ExecutorService executor;
    private final String baseUrl;

    private ExtractionTestApplication(HttpServer server, ExecutorService executor) {
        this.server = server;
        this.executor = executor;
        this.baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    static ExtractionTestApplication start() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/extract",
                exchange -> respond(exchange, page(exchange.getRequestURI().getPath())));
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        server.start();
        return new ExtractionTestApplication(server, executor);
    }

    String url(String route) {
        return baseUrl + route;
    }

    @Override
    public void close() {
        server.stop(0);
        executor.close();
    }

    private static String page(String path) {
        return switch (path) {
            case "/extract/simple-text" -> document("<h1>Total</h1><p id=\"amount\">42 USD</p>");
            case "/extract/unicode-text" ->
                    document("<h1 id=\"title\">Caf&eacute;&nbsp;&nbsp; M&uuml;nchen</h1>");
            case "/extract/attribute" ->
                    document("<a id=\"product-link\" href=\"/products/laptop-b\">Laptop B</a>");
            case "/extract/dynamic-value" ->
                    document(
                            "<input id=\"quantity\" value=\"1\">"
                                    + "<script>function setQuantity() {"
                                    + " document.getElementById('quantity').value = '5'; }</script>");
            case "/extract/list" ->
                    document(
                            "<ul>"
                                    + "<li>Laptop B</li>"
                                    + "<li>Mouse</li>"
                                    + "<li>Keyboard</li>"
                                    + "</ul>");
            case "/extract/empty-list" -> document("<ul id=\"products\"></ul>");
            case "/extract/table" ->
                    document(
                            "<table>"
                                    + "<thead><tr><th>Name</th><th>Price</th></tr></thead>"
                                    + "<tbody>"
                                    + "<tr><td>Laptop B</td><td>999</td></tr>"
                                    + "<tr><td>Mouse</td><td>19</td></tr>"
                                    + "</tbody>"
                                    + "</table>");
            case "/extract/table-no-thead" ->
                    document(
                            "<table><tbody>"
                                    + "<tr><td>Laptop B</td><td>999</td></tr>"
                                    + "</tbody></table>");
            case "/extract/empty-table" ->
                    document("<table><thead><tr><th>Name</th></tr></thead><tbody></tbody></table>");
            case "/extract/ragged-table" ->
                    document(
                            "<table>"
                                    + "<thead><tr><th>Name</th><th>Price</th><th>Stock</th></tr></thead>"
                                    + "<tbody>"
                                    + "<tr><td>Laptop B</td><td>999</td></tr>"
                                    + "</tbody></table>");
            case "/extract/nested-table" ->
                    document(
                            "<table id=\"outer\">"
                                    + "<thead><tr><th>Name</th><th>Price</th></tr></thead>"
                                    + "<tbody>"
                                    + "<tr><td>Laptop B</td><td>"
                                    + "<table id=\"inner\">"
                                    + "<thead><tr><th>Currency</th><th>Amount</th></tr></thead>"
                                    + "<tbody><tr><td>USD</td><td>999</td></tr></tbody>"
                                    + "</table>"
                                    + "</td></tr>"
                                    + "<tr><td>Mouse</td><td>19</td></tr>"
                                    + "</tbody></table>");
            case "/extract/ambiguous-table" ->
                    document(
                            "<table><thead><tr><th>Name</th></tr></thead>"
                                    + "<tbody><tr><td>Laptop B</td></tr></tbody></table>"
                                    + "<table><thead><tr><th>Name</th></tr></thead>"
                                    + "<tbody><tr><td>Mouse</td></tr></tbody></table>");
            case "/extract/missing-source" -> document("<p>No matching heading here</p>");
            case "/extract/ambiguous-source" ->
                    document("<button>Confirm</button><button>Confirm</button>");
            case "/extract/two-scoped-sections" ->
                    document(
                            "<section id=\"product-a\">"
                                    + "<p class=\"price\">10</p></section>"
                                    + "<section id=\"product-b\">"
                                    + "<p class=\"price\">20</p></section>");
            case "/extract/invalid-number" -> document("<p id=\"amount\">not a number</p>");
            case "/extract/missing-attribute" -> document("<a id=\"product-link\">Laptop B</a>");
            case "/extract/iframe-simple" ->
                    document(
                            "<iframe name=\"checkout\" src=\"/extract/iframe-child/checkout\"></iframe>");
            case "/extract/iframe-child/checkout" -> document("<p id=\"amount\">250 USD</p>");
            case "/extract/iframe-nested" ->
                    document(
                            "<iframe name=\"outer\" src=\"/extract/iframe-outer-child\"></iframe>");
            case "/extract/iframe-outer-child" ->
                    document(
                            "<iframe name=\"inner\" src=\"/extract/iframe-child/checkout\"></iframe>");
            case "/extract/iframe-siblings" ->
                    document(
                            "<iframe name=\"product-a\" src=\"/extract/iframe-child/product-a\"></iframe>"
                                    + "<iframe name=\"product-b\" src=\"/extract/iframe-child/product-b\"></iframe>");
            case "/extract/iframe-child/product-a" -> document("<p id=\"amount\">111 USD</p>");
            case "/extract/iframe-child/product-b" -> document("<p id=\"amount\">222 USD</p>");
            case "/extract/iframe-list" ->
                    document(
                            "<iframe name=\"catalog\" src=\"/extract/iframe-child/list\"></iframe>");
            case "/extract/iframe-child/list" ->
                    document("<ul><li>Laptop B</li><li>Mouse</li></ul>");
            case "/extract/iframe-nested-list" ->
                    document(
                            "<iframe name=\"outer-catalog\" src=\"/extract/iframe-outer-child-list\">"
                                    + "</iframe>");
            case "/extract/iframe-outer-child-list" ->
                    document(
                            "<iframe name=\"inner-catalog\" src=\"/extract/iframe-child/list\">"
                                    + "</iframe>");
            case "/extract/iframe-replacement" ->
                    document(
                            "<iframe name=\"checkout\" src=\"/extract/iframe-child/checkout\"></iframe>"
                                    + "<script>function replaceCheckoutFrame() {"
                                    + " var frame = document.querySelector('iframe[name=\"checkout\"]');"
                                    + " frame.remove();"
                                    + " var replacement = document.createElement('iframe');"
                                    + " replacement.name = 'checkout';"
                                    + " replacement.src = '/extract/iframe-child/checkout-v2';"
                                    + " document.body.appendChild(replacement); }</script>");
            case "/extract/iframe-child/checkout-v2" -> document("<p id=\"amount\">275 USD</p>");
            case "/extract/element-replaced-during-wait" ->
                    document(
                            "<p id=\"amount\">42 USD</p>"
                                    + "<script>function replaceAmount() {"
                                    + " var original = document.getElementById('amount');"
                                    + " var replacement = document.createElement('p');"
                                    + " replacement.id = 'amount';"
                                    + " replacement.textContent = '99 USD';"
                                    + " original.replaceWith(replacement); }</script>");
            default -> document("<p>Not found: " + path + "</p>");
        };
    }

    private static String document(String bodyHtml) {
        return "<!doctype html><html lang=\"en\"><head><title>Extraction fixture</title></head>"
                + "<body>"
                + bodyHtml
                + "</body></html>";
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, String body)
            throws IOException {
        byte[] bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
        exchange.sendResponseHeaders(200, bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }
}
