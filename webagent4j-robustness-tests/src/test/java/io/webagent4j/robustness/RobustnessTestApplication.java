package io.webagent4j.robustness;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

final class RobustnessTestApplication implements AutoCloseable {

    // Short enough that a satisfied oracle never overshoots its deadline by a perceptible
    // amount, long enough that polling never meaningfully loads the CPU across the bounded
    // waits this fixture actually needs (at most a second or two).
    private static final long POLL_INTERVAL_NANOS = Duration.ofMillis(1).toNanos();

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

    /**
     * The exact URL a fixture's own JavaScript click handler calls to report {@code target} -
     * exposed so a pure-Java unit test can drive this application's real {@code /track} endpoint
     * directly (over a real, if local, HTTP round-trip) without needing a browser at all, the same
     * way {@link #awaitExecution} is exercised in isolation from any Playwright click.
     */
    String trackUrl(String target) {
        return baseUrl + "/track?target=" + URLEncoder.encode(target, StandardCharsets.UTF_8);
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

    /**
     * Blocks the calling thread, for at most {@code timeout}, until an already-triggered {@code
     * /track} side effect is actually observed on {@link #actualTarget()}/{@link #executionCount()}
     * - it never performs a browser action, never calls {@code /track} itself, and never mutates
     * the observed state; it only reads the same {@link AtomicReference}/{@link AtomicInteger}
     * {@link #actualTarget()} and {@link #executionCount()} already expose.
     *
     * <p>A fixture's click handler fires its tracking {@code fetch('/track?...')} asynchronously,
     * with no happens-before relationship to the click call returning. Reading {@link
     * #actualTarget()} immediately afterward races that fetch: on a browser whose fetch dispatch
     * has more scheduling latency than the click itself, the local HTTP round-trip may simply not
     * have completed yet, even though the click genuinely already fired it. This oracle replaces
     * that immediate read with a bounded, deterministic wait for the side effect's arrival - never
     * a replay of the click, and never an unbounded wait.
     *
     * <p>Fails immediately, before {@code timeout} elapses, the moment the observed state can no
     * longer reach the expected outcome: a non-empty target that does not match {@code
     * expectedTarget} (some other target fired), or a count already past {@code expectedCount} (a
     * duplicate side effect). A caller thread interruption - already set before this call, or
     * delivered while it is parked - is never swallowed: the flag is left set (this method never
     * consumes it, only observes it) and the wait ends immediately rather than completing the
     * deadline.
     *
     * @throws AssertionError if the expected target/count is never jointly observed within {@code
     *     timeout}, if a wrong target or a duplicate execution is observed first, or if the calling
     *     thread is interrupted before or during the wait
     */
    void awaitExecution(String expectedTarget, int expectedCount, Duration timeout) {
        long deadlineNanos = System.nanoTime() + timeout.toNanos();
        while (true) {
            String observedTarget = actualTarget.get();
            int observedCount = executionCount.get();

            if (observedCount > expectedCount) {
                throw new AssertionError(
                        "duplicate execution while awaiting target \""
                                + expectedTarget
                                + "\": expected count "
                                + expectedCount
                                + " but observed "
                                + observedCount);
            }
            if (!observedTarget.isEmpty() && !observedTarget.equals(expectedTarget)) {
                throw new AssertionError(
                        "wrong target observed: expected \""
                                + expectedTarget
                                + "\" but observed \""
                                + observedTarget
                                + "\"");
            }
            if (observedCount == expectedCount && observedTarget.equals(expectedTarget)) {
                return;
            }
            if (Thread.currentThread().isInterrupted()) {
                throw new AssertionError(
                        "interrupted while awaiting target \"" + expectedTarget + "\"");
            }
            long remainingNanos = deadlineNanos - System.nanoTime();
            if (remainingNanos <= 0) {
                throw new AssertionError(
                        "timed out after "
                                + timeout
                                + " awaiting target \""
                                + expectedTarget
                                + "\" with count "
                                + expectedCount
                                + "; last observed target \""
                                + observedTarget
                                + "\" with count "
                                + observedCount);
            }
            LockSupport.parkNanos(Math.min(remainingNanos, POLL_INTERVAL_NANOS));
        }
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
