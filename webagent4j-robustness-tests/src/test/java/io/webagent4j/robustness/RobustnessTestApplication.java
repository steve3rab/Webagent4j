package io.webagent4j.robustness;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

final class RobustnessTestApplication implements AutoCloseable {

    private final HttpServer server;
    private final ExecutorService executor;
    private final String baseUrl;
    private final AtomicReference<String> actualTarget;
    private final AtomicInteger executionCount;

    private RobustnessTestApplication(
            HttpServer server,
            ExecutorService executor,
            AtomicReference<String> actualTarget,
            AtomicInteger executionCount) {
        this.server = server;
        this.executor = executor;
        this.actualTarget = actualTarget;
        this.executionCount = executionCount;
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    static RobustnessTestApplication start() throws IOException {
        AtomicReference<String> target = new AtomicReference<>("");
        AtomicInteger count = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/fixture/", RobustnessTestApplication::fixture);
        server.createContext(
                "/track",
                exchange -> {
                    String value = queryParameter(exchange.getRequestURI().getRawQuery(), "target");
                    target.set(value);
                    count.incrementAndGet();
                    respond(exchange, 200, "text/plain; charset=utf-8", "tracked");
                });
        server.createContext(
                "/state",
                exchange ->
                        respond(
                                exchange,
                                200,
                                "text/plain; charset=utf-8",
                                target.get() + "|" + count.get()));
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        server.start();
        return new RobustnessTestApplication(server, executor, target, count);
    }

    String fixtureUrl(String fixture) {
        return baseUrl + "/fixture/" + fixture;
    }

    void reset() {
        actualTarget.set("");
        executionCount.set(0);
    }

    String actualTarget() {
        return actualTarget.get();
    }

    int executionCount() {
        return executionCount.get();
    }

    @Override
    public void close() {
        server.stop(0);
        executor.close();
    }

    private static void fixture(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath().substring("/fixture/".length());
        if (path.isBlank() || path.contains("..") || path.contains("\\")) {
            respond(exchange, 400, "text/plain; charset=utf-8", "invalid fixture");
            return;
        }
        String resource = "robustness/" + path;
        try (InputStream input =
                RobustnessTestApplication.class.getClassLoader().getResourceAsStream(resource)) {
            if (input == null) {
                respond(exchange, 404, "text/plain; charset=utf-8", "fixture not found");
                return;
            }
            respond(
                    exchange,
                    200,
                    "text/html; charset=utf-8",
                    new String(input.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    private static String queryParameter(String query, String name) {
        if (query == null) {
            return "";
        }
        for (String pair : query.split("&")) {
            String[] parts = pair.split("=", 2);
            if (parts.length == 2 && parts[0].equals(name)) {
                return URLDecoder.decode(parts[1], StandardCharsets.UTF_8);
            }
        }
        return "";
    }

    private static void respond(HttpExchange exchange, int status, String contentType, String body)
            throws IOException {
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, payload.length);
        try (var output = exchange.getResponseBody()) {
            output.write(payload);
        }
    }
}
