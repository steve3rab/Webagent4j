package io.webagent4j.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.webagent4j.action.ActionResult;
import io.webagent4j.browser.IBrowser;
import io.webagent4j.browser.IPage;
import io.webagent4j.core.WebAgent;
import io.webagent4j.dom.IElement;
import io.webagent4j.observation.Observation;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class PlaywrightBrowserIT {

    private static HttpServer server;
    private static ExecutorService executor;
    private static String baseUrl;

    @BeforeAll
    static void startTestApplication() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/",
                exchange ->
                        respond(
                                exchange,
                                """
                                <!doctype html>
                                <html lang="en">
                                  <head><title>Local Example</title></head>
                                  <body>
                                    <main>
                                      <h1>Example Domain</h1>
                                      <a href="/iana">More information</a>
                                    </main>
                                  </body>
                                </html>
                                """));
        server.createContext(
                "/iana",
                exchange ->
                        respond(
                                exchange,
                                """
                                <!doctype html>
                                <html lang="en">
                                  <head><title>IANA Information</title></head>
                                  <body><main><h1>Navigation completed</h1></main></body>
                                </html>
                                """));
        executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterAll
    static void stopTestApplication() {
        server.stop(0);
        executor.close();
    }

    @Test
    void completesTheFirstSemanticVertical() {
        try (IBrowser browser =
                WebAgent.browser().playwright().chromium().headless(true).launch()) {
            IPage page = browser.open(baseUrl);

            Observation observation = page.observe();
            IElement link = page.find().link().named("More information").first();
            ActionResult<Void> action =
                    page.action().click(link).expectUrlContains("/iana").execute();

            assertThat(observation.title()).isEqualTo("Local Example");
            assertThat(observation.links()).extracting("name").containsExactly("More information");
            assertThat(action.success()).isTrue();
            assertThat(page.title()).isEqualTo("IANA Information");
        }
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
