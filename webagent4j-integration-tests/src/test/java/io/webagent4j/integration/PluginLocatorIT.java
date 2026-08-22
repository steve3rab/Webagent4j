package io.webagent4j.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.webagent4j.browser.IBrowser;
import io.webagent4j.browser.IPage;
import io.webagent4j.core.WebAgent;
import io.webagent4j.dom.IElement;
import io.webagent4j.integration.plugin.TestLocatorStrategyProvider;
import io.webagent4j.locator.ILocatorBackend;
import io.webagent4j.locator.LocatorBackendCandidate;
import io.webagent4j.locator.LocatorBackendQuery;
import io.webagent4j.locator.LocatorBackendSearchResult;
import io.webagent4j.locator.LocatorConfig;
import io.webagent4j.locator.LocatorContext;
import io.webagent4j.locator.LocatorEngine;
import io.webagent4j.locator.LocatorResult;
import io.webagent4j.locator.LocatorScope;
import io.webagent4j.locator.LocatorStrategyType;
import io.webagent4j.locator.api.ElementRole;
import io.webagent4j.locator.api.LocatorDefinition;
import io.webagent4j.plugin.PluginLoader;
import io.webagent4j.plugin.PluginRegistry;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PluginLocatorIT {

    private static HttpServer server;
    private static ExecutorService executor;
    private static String pageUrl;

    @BeforeAll
    static void startApplication() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/plugins", PluginLocatorIT::respond);
        executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        server.start();
        pageUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/plugins";
    }

    @AfterAll
    static void stopApplication() {
        server.stop(0);
        executor.close();
    }

    @BeforeEach
    void resetProvider() {
        TestLocatorStrategyProvider.reset();
    }

    @Test
    void explicitlyLoadsCustomStrategyAgainstARealPlaywrightPage() {
        try (IBrowser browser =
                WebAgent.browser().playwright().chromium().headless(true).launch()) {
            IPage page = browser.open(pageUrl);
            LocatorContext context =
                    LocatorContext.page(
                            new PageBridgeBackend(page),
                            LocatorConfig.defaults(Duration.ofSeconds(1)));

            LocatorResult defaultResult =
                    new LocatorEngine()
                            .locate(context, LocatorDefinition.element().withId("standard-target"));
            assertThat(defaultResult.element().text()).isEqualTo("Standard target");
            assertThat(TestLocatorStrategyProvider.constructorCalls()).isZero();

            PluginRegistry plugins = new PluginLoader().load();
            LocatorEngine pluginAware = new LocatorEngine(plugins.locatorStrategyRegistry());
            LocatorResult customResult =
                    pluginAware.locate(
                            context,
                            LocatorDefinition.forRole(ElementRole.BUTTON).named("Plugin route"));
            LocatorResult standardResult =
                    pluginAware.locate(
                            context, LocatorDefinition.element().withId("standard-target"));

            assertThat(plugins.plugins())
                    .extracting(descriptor -> descriptor.id().value())
                    .contains("test-playwright-locator");
            assertThat(customResult.strategy()).isEqualTo(LocatorStrategyType.CUSTOM);
            assertThat(customResult.element().attributes())
                    .containsEntry("data-testid", "plugin-target");
            assertThat(standardResult.strategy()).isEqualTo(LocatorStrategyType.ID);
            assertThat(standardResult.element().text()).isEqualTo("Standard target");
            assertThat(TestLocatorStrategyProvider.supportsCalls()).isPositive();
            assertThat(TestLocatorStrategyProvider.discoverCalls()).isPositive();
        }
    }

    private static void respond(HttpExchange exchange) throws IOException {
        byte[] payload =
                """
                <!doctype html>
                <html lang="en">
                  <head><title>Plugin locator fixture</title></head>
                  <body>
                    <button id="standard-target">Standard target</button>
                    <button data-testid="plugin-target">Resolved by plugin</button>
                  </body>
                </html>
                """
                        .getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        exchange.sendResponseHeaders(200, payload.length);
        try (var output = exchange.getResponseBody()) {
            output.write(payload);
        }
    }

    private record PageBridgeBackend(IPage page) implements ILocatorBackend {

        private PageBridgeBackend {
            java.util.Objects.requireNonNull(page, "page");
        }

        @Override
        public LocatorBackendSearchResult find(
                LocatorBackendQuery query,
                LocatorScope scope,
                LocatorConfig config,
                Duration timeout,
                int candidateLimit) {
            List<IElement> elements =
                    switch (query.strategy()) {
                        case ID -> page.find().id(query.value().orElseThrow()).all();
                        case TEST_ID -> page.find().testId(query.value().orElseThrow()).all();
                        default -> List.of();
                    };
            List<LocatorBackendCandidate> candidates =
                    IntStream.range(0, Math.min(elements.size(), candidateLimit))
                            .mapToObj(
                                    index ->
                                            new LocatorBackendCandidate(
                                                    "playwright-" + index,
                                                    elements.get(index),
                                                    index))
                            .toList();
            return new LocatorBackendSearchResult(
                    candidates, elements.size(), candidates.size() < elements.size());
        }
    }
}
