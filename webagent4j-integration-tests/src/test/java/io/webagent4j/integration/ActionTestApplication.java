package io.webagent4j.integration;

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

final class ActionTestApplication implements AutoCloseable {

    private final HttpServer server;
    private final ExecutorService executor;
    private final String baseUrl;
    private final AtomicInteger clickCount;
    private final Map<String, AtomicInteger> namedClickCounts;

    private ActionTestApplication(
            HttpServer server,
            ExecutorService executor,
            AtomicInteger clickCount,
            Map<String, AtomicInteger> namedClickCounts) {
        this.server = server;
        this.executor = executor;
        this.clickCount = clickCount;
        this.namedClickCounts = namedClickCounts;
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    static ActionTestApplication start() throws IOException {
        AtomicInteger count = new AtomicInteger();
        Map<String, AtomicInteger> namedCounts = new ConcurrentHashMap<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/download/file", ActionTestApplication::download);
        server.createContext(
                "/test-state/click-count", exchange -> text(exchange, count.toString()));
        server.createContext(
                "/test-state/reset",
                exchange -> {
                    count.set(0);
                    namedCounts.clear();
                    text(exchange, "reset");
                });
        server.createContext(
                "/actions",
                exchange -> {
                    String path = exchange.getRequestURI().getPath();
                    html(exchange, page(path));
                });
        server.createContext("/login", exchange -> html(exchange, loginPage()));
        server.createContext("/dashboard", exchange -> html(exchange, dashboardPage()));
        server.createContext("/navigation/one", exchange -> html(exchange, navigationPage("One")));
        server.createContext("/navigation/two", exchange -> html(exchange, navigationPage("Two")));
        server.createContext(
                "/count-click",
                exchange -> {
                    String path = exchange.getRequestURI().getPath();
                    String prefix = "/count-click";
                    String name =
                            path.length() > prefix.length() + 1
                                    ? path.substring(prefix.length() + 1)
                                    : "";
                    if (name.isEmpty()) {
                        count.incrementAndGet();
                    } else {
                        namedCounts
                                .computeIfAbsent(name, key -> new AtomicInteger())
                                .incrementAndGet();
                    }
                    text(exchange, "ok");
                });
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        server.start();
        return new ActionTestApplication(server, executor, count, namedCounts);
    }

    String url(String route) {
        return baseUrl + route;
    }

    int clickCount() {
        return clickCount.get();
    }

    /**
     * Returns the independent click count recorded under a named {@code /count-click/<name>} hit.
     */
    int clickCount(String name) {
        AtomicInteger recorded = namedClickCounts.get(name);
        return recorded == null ? 0 : recorded.get();
    }

    @Override
    public void close() {
        server.stop(0);
        executor.close();
    }

    private static String page(String path) {
        return switch (path) {
            case "/actions/type" ->
                    document(
                            "Type actions",
                            """
                            <label for="email">Email</label><input id="email" type="email">
                            <label for="password">Password</label><input id="password" type="password">
                            <p id="typed">Ready</p>
                            """);
            case "/actions/select" ->
                    document(
                            "Select actions",
                            """
                            <label for="country">Country</label><select id="country">
                              <option value="fr">France</option><option value="de">Germany</option>
                            </select><p id="selection">Ready</p>
                            """);
            case "/actions/checkbox" ->
                    document(
                            "Checkbox actions",
                            "<label><input type="
                                    + "\"checkbox\" name=\"remember\"> Remember me</label>");
            case "/actions/hover" ->
                    document(
                            "Hover actions",
                            """
                            <button onmouseenter="tip.hidden=false">Show details</button>
                            <div id="tip" role="status" hidden>Helpful details</div>
                            """);
            case "/actions/focus", "/actions/keyboard" ->
                    document(
                            "Keyboard actions",
                            """
                            <label for="first">First</label><input id="first">
                            <label for="second">Second</label><input id="second"
                              onkeydown="if(event.key==='Enter') done.hidden=false">
                            <p id="done" hidden>Submitted</p>
                            """);
            case "/actions/scroll" ->
                    document(
                            "Scroll actions",
                            "<div style=\"height:1800px\"></div><button "
                                    + "onclick=\"result.hidden=false\">Far action</button>"
                                    + "<p id=\"result\" hidden>Reached</p>");
            case "/actions/navigation" -> navigationPage("Actions navigation");
            case "/actions/upload" ->
                    document(
                            "Upload actions",
                            """
                            <label for="file">Document</label><input id="file" type="file"
                              onchange="filename.textContent=this.files[0].name">
                            <p id="filename">No file</p>
                            """);
            case "/actions/download" ->
                    document(
                            "Download actions",
                            "<a href=\"/download/file\" download>Download report</a>");
            case "/actions/dynamic-target" ->
                    document(
                            "Dynamic target",
                            """
                            <button id="confirm" onclick="result.hidden=false">Confirm</button>
                            <p id="result" hidden>Confirmed</p>
                            <script>setTimeout(() => {
                              const old = document.getElementById('confirm');
                              const fresh = document.createElement('button');
                              fresh.id='fresh'; fresh.textContent='Confirm';
                              fresh.onclick=() => result.hidden=false; old.replaceWith(fresh);
                            }, 150)</script>
                            """);
            case "/actions/plan-same-target" ->
                    document(
                            "Plan same target",
                            """
                            <button id="confirm" onclick="fetch('/count-click')">Confirm</button>
                            <script>setTimeout(() => {
                              const old = document.getElementById('confirm');
                              const fresh = document.createElement('button');
                              fresh.id='fresh'; fresh.textContent='Confirm';
                              fresh.onclick=() => fetch('/count-click'); old.replaceWith(fresh);
                            }, 150)</script>
                            """);
            case "/actions/plan-wrong-target" ->
                    document(
                            "Plan wrong target",
                            """
                            <button id="confirm" onclick="fetch('/count-click')">Confirm</button>
                            <script>setTimeout(() => {
                              const old = document.getElementById('confirm');
                              const wrong = document.createElement('button');
                              wrong.id='delete'; wrong.textContent='Delete';
                              wrong.onclick=() => fetch('/count-click'); old.replaceWith(wrong);
                            }, 150)</script>
                            """);
            case "/actions/plan-ambiguity" ->
                    document(
                            "Plan ambiguity",
                            """
                            <div id="host"><button onclick="fetch('/count-click')">Confirm</button></div>
                            <script>setTimeout(() => {
                              const duplicate = document.createElement('button');
                              duplicate.textContent='Confirm';
                              duplicate.onclick=() => fetch('/count-click');
                              host.appendChild(duplicate);
                            }, 150)</script>
                            """);
            case "/actions/plan-precondition-invalidates" ->
                    document(
                            "Plan precondition invalidates",
                            """
                            <button id="confirm" onclick="fetch('/count-click')">Confirm</button>
                            <script>setTimeout(() => { confirm.disabled = true; }, 150)</script>
                            """);
            case "/actions/context-multi" ->
                    document(
                            "Context multi",
                            """
                            <section aria-label="Laptop A">
                              <h2>Laptop A</h2>
                              <div aria-label="Unavailable"><span>Unavailable</span>
                                <button onclick="fetch('/count-click/laptopA-unavailable')">Ajouter</button></div>
                              <div aria-label="Available"><span>Available</span>
                                <button onclick="fetch('/count-click/laptopA-available')">Ajouter</button></div>
                            </section>
                            <section aria-label="Laptop B">
                              <h2>Laptop B</h2>
                              <div aria-label="Unavailable"><span>Unavailable</span>
                                <button onclick="fetch('/count-click/laptopB-unavailable')">Ajouter</button></div>
                              <div aria-label="Available"><span>Available</span>
                                <button onclick="fetch('/count-click/laptopB-available')">Ajouter</button></div>
                            </section>
                            """);
            case "/actions/context-ambiguous" ->
                    document(
                            "Context ambiguous",
                            """
                            <section aria-label="Shipping"><button
                              onclick="fetch('/count-click/shipping-1')">Continue</button></section>
                            <section aria-label="Shipping"><button
                              onclick="fetch('/count-click/shipping-2')">Continue</button></section>
                            """);
            case "/actions/context-dynamic-ambiguous" ->
                    document(
                            "Context dynamic ambiguous",
                            """
                            <section id="shipping-1" aria-label="Shipping"><button
                              onclick="fetch('/count-click/shipping-1')">Continue</button></section>
                            <script>setTimeout(() => {
                              const duplicate = document.createElement('section');
                              duplicate.setAttribute('aria-label', 'Shipping');
                              duplicate.innerHTML =
                                '<button onclick="fetch(\\'/count-click/shipping-2\\')">Continue</button>';
                              document.body.appendChild(duplicate);
                            }, 150)</script>
                            """);
            case "/actions/context-dynamic-disappears" ->
                    document(
                            "Context dynamic disappears",
                            """
                            <section id="shipping-solo" aria-label="Shipping"><button
                              onclick="fetch('/count-click/shipping-solo')">Continue</button></section>
                            <script>setTimeout(() => {
                              document.getElementById('shipping-solo').remove();
                            }, 150)</script>
                            """);
            case "/actions/context-dynamic-replaced" ->
                    document(
                            "Context dynamic replaced",
                            """
                            <section id="shipping-old" aria-label="Shipping"><button
                              onclick="fetch('/count-click/shipping-continue')">Continue</button></section>
                            <script>setTimeout(() => {
                              const old = document.getElementById('shipping-old');
                              const fresh = document.createElement('section');
                              fresh.id = 'shipping-fresh';
                              fresh.setAttribute('aria-label', 'Shipping');
                              fresh.innerHTML =
                                '<button onclick="fetch(\\'/count-click/shipping-continue\\')">Continue</button>';
                              old.replaceWith(fresh);
                            }, 150)</script>
                            """);
            case "/actions/context-dynamic-semantic-change" ->
                    document(
                            "Context dynamic semantic change",
                            """
                            <section id="shipping-old" aria-label="Shipping"><button
                              onclick="fetch('/count-click/shipping-continue')">Continue</button></section>
                            <script>setTimeout(() => {
                              const old = document.getElementById('shipping-old');
                              const billing = document.createElement('section');
                              billing.setAttribute('aria-label', 'Billing');
                              billing.innerHTML =
                                '<button onclick="fetch(\\'/count-click/billing-continue\\')">Continue</button>';
                              old.replaceWith(billing);
                            }, 150)</script>
                            """);
            case "/actions/context-dynamic-nested-ambiguous" ->
                    document(
                            "Context dynamic nested ambiguous",
                            """
                            <section aria-label="Laptop B">
                              <h2>Laptop B</h2>
                              <div id="laptopB-available" aria-label="Available"><span>Available</span>
                                <button onclick="fetch('/count-click/laptopB-available')">Ajouter</button></div>
                            </section>
                            <script>setTimeout(() => {
                              const section = document.querySelector('section[aria-label="Laptop B"]');
                              const duplicate = document.createElement('div');
                              duplicate.setAttribute('aria-label', 'Available');
                              duplicate.innerHTML =
                                '<span>Available</span><button '
                                + 'onclick="fetch(\\'/count-click/laptopB-available-2\\')">Ajouter</button>';
                              section.appendChild(duplicate);
                            }, 150)</script>
                            """);
            case "/actions/delayed-result", "/actions/retry" ->
                    document(
                            "Delayed result",
                            """
                            <button onclick="fetch('/count-click'); setTimeout(() => done.hidden=false, 650)">
                              Process once</button><p id="done" hidden>Completed once</p>
                            """);
            case "/actions/dialog" ->
                    document(
                            "Dialog actions",
                            """
                            <button onclick="notice.showModal()">Open notifications</button>
                            <dialog id="notice" aria-label="Notifications"><h2>Notifications</h2>
                              <button onclick="notice.close()">Close</button></dialog>
                            """);
            case "/actions/failure" ->
                    document(
                            "Failure actions",
                            "<button disabled>Disabled action</button><p>Still usable</p>");
            case "/actions/overlay" ->
                    document(
                            "Overlay actions",
                            """
                            <button id="covered">Covered action</button>
                            <div style="position:fixed;inset:0;background:#fff8;z-index:2"></div>
                            """);
            case "/actions/form" -> loginPage();
            case "/actions/ambiguous" ->
                    document(
                            "Ambiguous actions",
                            "<button>Duplicate</button><button>Duplicate</button>");
            default ->
                    document(
                            "Click actions",
                            """
                            <p id="counter">0</p><button onclick="counter.textContent='1'">Increment</button>
                            <button onclick="this.innerHTML='<span>Resilient done</span>'"
                              class="before" id="generated-1">Resilient action</button>
                            """);
        };
    }

    private static String loginPage() {
        return document(
                "Sign in",
                """
                <form aria-label="Sign in" onsubmit="event.preventDefault(); location='/dashboard'">
                  <label for="email">Email</label><input id="email" type="email" required>
                  <label for="password">Password</label><input id="password" type="password" required>
                  <label><input type="checkbox" name="remember"> Remember me</label>
                  <button type="submit">Sign in</button>
                </form>
                """);
    }

    private static String dashboardPage() {
        return document(
                "Dashboard",
                """
                <nav aria-label="Primary"><a href="/navigation/one">Home</a></nav>
                <h1>Welcome</h1><p>user@example.test</p>
                <button onclick="notice.showModal()">Open notifications</button>
                <dialog id="notice" aria-label="Notifications"><h2>Notifications</h2></dialog>
                """);
    }

    private static String navigationPage(String title) {
        return document(
                title, "<h1>" + title + "</h1><a href=\"/navigation/two\">Continue navigation</a>");
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

    private static void download(HttpExchange exchange) throws IOException {
        byte[] payload = "WebAgent4J download fixture\n".getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain");
        exchange.getResponseHeaders().set("Content-Disposition", "attachment; filename=report.txt");
        exchange.sendResponseHeaders(200, payload.length);
        try (var output = exchange.getResponseBody()) {
            output.write(payload);
        }
    }

    private static void html(HttpExchange exchange, String body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        respond(exchange, body);
    }

    private static void text(HttpExchange exchange, String body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        respond(exchange, body);
    }

    private static void respond(HttpExchange exchange, String body) throws IOException {
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, payload.length);
        try (var output = exchange.getResponseBody()) {
            output.write(payload);
        }
    }
}
