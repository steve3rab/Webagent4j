package io.webagent4j.integration;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class ObservationTestApplication implements AutoCloseable {

    private final HttpServer server;
    private final ExecutorService executor;
    private final String baseUrl;

    private ObservationTestApplication(HttpServer server, ExecutorService executor) {
        this.server = server;
        this.executor = executor;
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    static ObservationTestApplication start() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/observation/basic", exchange -> respond(exchange, basicPage()));
        server.createContext("/observation/landmarks", exchange -> respond(exchange, basicPage()));
        server.createContext("/observation/navigation", exchange -> respond(exchange, basicPage()));
        server.createContext("/observation/forms", exchange -> respond(exchange, formsPage()));
        server.createContext("/observation/tables", exchange -> respond(exchange, basicPage()));
        server.createContext("/observation/lists", exchange -> respond(exchange, basicPage()));
        server.createContext("/observation/dialogs", exchange -> respond(exchange, basicPage()));
        server.createContext("/observation/tabs", exchange -> respond(exchange, basicPage()));
        server.createContext("/observation/dynamic", exchange -> respond(exchange, dynamicPage()));
        server.createContext("/observation/sensitive", exchange -> respond(exchange, formsPage()));
        server.createContext("/observation/large", exchange -> respond(exchange, largePage()));
        server.createContext(
                "/observation/accessibility", exchange -> respond(exchange, accessibilityPage()));
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        server.start();
        return new ObservationTestApplication(server, executor);
    }

    String url(String route) {
        return baseUrl + route;
    }

    @Override
    public void close() {
        server.stop(0);
        executor.close();
    }

    private static String basicPage() {
        return """
                <!doctype html>
                <html lang="en">
                  <head>
                    <title>Observation fixture</title>
                    <meta name="description" content="Semantic observation fixture">
                    <link rel="canonical" href="/observation/basic">
                  </head>
                  <body>
                    <header>WebAgent4J</header>
                    <nav aria-label="Primary" aria-orientation="horizontal">
                      <a href="#overview" aria-current="page">Overview</a>
                    </nav>
                    <main aria-label="Dashboard">
                      <h1>Dashboard</h1>
                      <h2>Activity</h2>
                      <p>Welcome to the semantic observation fixture.</p>
                      <button data-testid="refresh" onclick="document.body.dataset.clicked='yes'">
                        Refresh data
                      </button>
                      <form aria-label="Search catalog"><label for="query">Query</label>
                        <input id="query" type="search"><button type="submit">Search</button>
                      </form>
                      <ol aria-label="Steps"><li>Observe</li><li>Act</li><li>Verify</li></ol>
                      <table aria-label="Invoices">
                        <thead><tr><th>Number</th><th>Total</th></tr></thead>
                        <tbody><tr><td>A-1</td><td>10 EUR</td></tr></tbody>
                      </table>
                      <img alt="Company logo" src="data:image/gif;base64,R0lGODlhAQABAAAAACw=">
                      <div role="alert">Data is current</div>
                      <div role="tablist" aria-label="Views">
                        <button role="tab" aria-selected="true" aria-controls="summary">Summary</button>
                        <section id="summary" role="tabpanel" aria-label="Summary">Summary panel</section>
                      </div>
                      <div role="menu" aria-label="Account menu">
                        <button role="menuitem">Profile</button>
                      </div>
                      <dialog open aria-label="Notice"><button>Close notice</button></dialog>
                    </main>
                    <footer>Copyright</footer>
                  </body>
                </html>
                """;
    }

    private static String formsPage() {
        return """
                <!doctype html>
                <html lang="en">
                  <head><title>Secure form observation</title></head>
                  <body>
                    <main>
                      <form aria-label="Sign in" action="/session" method="post">
                        <label for="email">Email address</label>
                        <input id="email" name="email" type="email" value="user@example.test"
                          placeholder="name@example.test" required>
                        <label for="password">Password</label>
                        <input id="password" name="password" type="password"
                          value="WEBAGENT4J_SECRET_TEST_VALUE" autocomplete="current-password">
                        <label for="token">API token</label>
                        <input id="token" name="api_token" value="literal-token-secret">
                        <label for="country">Country</label>
                        <select id="country" name="country">
                          <option>France</option><option>Germany</option><option>Italy</option>
                        </select>
                        <label><input type="checkbox" name="remember"> Remember me</label>
                        <label><input type="radio" name="plan" value="standard"> Standard plan</label>
                        <label for="notes">Notes</label><textarea id="notes" readonly>Read only</textarea>
                        <input aria-label="Disabled code" value="disabled" disabled>
                        <button type="submit" data-testid="sign-in">Sign in</button>
                      </form>
                    </main>
                  </body>
                </html>
                """;
    }

    private static String dynamicPage() {
        return """
                <!doctype html>
                <html lang="en">
                  <head><title>Dynamic observation</title></head>
                  <body>
                    <main>
                      <h1>Notifications</h1>
                      <button id="target">Load notifications</button>
                      <button id="remove-me">Dismiss old notification</button>
                      <div role="tablist" aria-label="Notification views">
                        <button id="unread-tab" role="tab" aria-selected="true">Unread</button>
                        <button id="all-tab" role="tab" aria-selected="false">All</button>
                      </div>
                      <dialog id="notification-dialog" aria-label="Notification details">
                        <button>Close details</button>
                      </dialog>
                      <div id="messages"></div>
                    </main>
                    <script>
                      function mutateObservationPage() {
                        document.getElementById('target').textContent = 'Notifications loaded';
                        document.getElementById('remove-me').remove();
                        document.getElementById('unread-tab').setAttribute('aria-selected', 'false');
                        document.getElementById('all-tab').setAttribute('aria-selected', 'true');
                        document.getElementById('notification-dialog').showModal();
                        const status = document.createElement('div');
                        status.setAttribute('role', 'status');
                        status.textContent = 'Three notifications';
                        document.getElementById('messages').appendChild(status);
                        document.title = 'Notifications loaded';
                      }
                    </script>
                  </body>
                </html>
                """;
    }

    private static String largePage() {
        StringBuilder result =
                new StringBuilder(
                        "<!doctype html><html lang=\"en\"><head><title>Large observation</title>"
                                + "</head><body><main><h1>Catalog</h1>");
        for (int index = 0; index < 1_000; index++) {
            result.append("<button data-testid=\"item-")
                    .append(index)
                    .append("\">Item ")
                    .append(index)
                    .append("</button>");
        }
        return result.append("</main></body></html>").toString();
    }

    private static String accessibilityPage() {
        return """
                <!doctype html>
                <html lang="en">
                  <head><title>Accessibility edges</title></head>
                  <body>
                    <main>
                      <h1>Accessible content</h1><h3>Skipped level</h3>
                      <button></button>
                      <label>Unbound label</label><input name="unlabelled">
                      <img src="data:image/gif;base64,R0lGODlhAQABAAAAACw=">
                      <button hidden>Hidden action</button>
                      <div role="status">Ready</div>
                    </main>
                  </body>
                </html>
                """;
    }

    private static void respond(HttpExchange exchange, String body) throws IOException {
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        exchange.sendResponseHeaders(200, payload.length);
        try (var output = exchange.getResponseBody()) {
            output.write(payload);
        }
    }
}
