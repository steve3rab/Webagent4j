package io.webagent4j.examples;

import io.webagent4j.browser.IBrowser;
import io.webagent4j.browsercrawler.BrowserCrawlRequest;
import io.webagent4j.browsercrawler.BrowserCrawlResult;
import io.webagent4j.browsercrawler.BrowserCrawler;
import io.webagent4j.browsercrawler.CancellationToken;
import io.webagent4j.core.WebAgent;
import java.time.Duration;

/**
 * Demonstrates session reuse and cancellation: every page this crawl navigates shares the same
 * {@code IBrowser}'s cookies/storage/authentication state, and a {@link CancellationToken} can stop
 * an in-progress crawl from another thread without forcibly aborting an in-flight navigation.
 *
 * <p>In a real application, authenticate {@code browser} (log in, accept a consent cookie, etc.)
 * before building the request - the crawler never does this itself.
 */
public final class BrowserCrawlSessionExample {

    private BrowserCrawlSessionExample() {}

    public static void main(String[] args) {
        String seedUrl = requireArgument(args, "seed URL");

        try (IBrowser browser =
                WebAgent.browser().playwright().chromium().headless(true).launch()) {
            CancellationToken cancellationToken = CancellationToken.create();

            BrowserCrawlRequest request =
                    BrowserCrawlRequest.builder(browser)
                            .seed(seedUrl)
                            .maxDepth(3)
                            .maxPages(100)
                            .maxConcurrency(2)
                            .navigationTimeout(Duration.ofSeconds(20))
                            .cancellationToken(cancellationToken)
                            .build();

            // A caller could call cancellationToken.cancel() from another thread here - for
            // example,
            // in response to a user action or an external deadline - to stop the crawl early while
            // still keeping every already-claimed page's result.
            BrowserCrawlResult result = new BrowserCrawler().crawl(request);

            System.out.println(
                    result.pages().size()
                            + " pages navigated, "
                            + result.failures().size()
                            + " failed, terminated: "
                            + result.terminationReason());
        }
    }

    private static String requireArgument(String[] args, String description) {
        if (args.length == 0 || args[0].isBlank()) {
            throw new IllegalArgumentException(
                    "Expected " + description + " as the first argument");
        }
        return args[0];
    }
}
