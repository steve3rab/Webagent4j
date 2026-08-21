package io.webagent4j.examples;

import io.webagent4j.browser.IBrowser;
import io.webagent4j.browsercrawler.BrowserCrawlRequest;
import io.webagent4j.browsercrawler.BrowserCrawlResult;
import io.webagent4j.browsercrawler.BrowserCrawler;
import io.webagent4j.core.WebAgent;

/**
 * Demonstrates the simplest browser crawl: a bounded, same-host breadth-first crawl through a real
 * (headless) browser, following JavaScript-rendered {@code <a href>} links that a static-HTML crawl
 * ({@link HttpCrawlSimpleExample}) would miss.
 */
public final class BrowserCrawlSimpleExample {

    private BrowserCrawlSimpleExample() {}

    /** Crawls the given seed URL up to depth 2, printing every page's depth, URL, and title. */
    public static void main(String[] args) {
        String seedUrl = requireArgument(args, "seed URL");

        try (IBrowser browser =
                WebAgent.browser().playwright().chromium().headless(true).launch()) {
            BrowserCrawlRequest request =
                    BrowserCrawlRequest.builder(browser)
                            .seed(seedUrl)
                            .maxDepth(2)
                            .maxPages(20)
                            .build();

            BrowserCrawlResult result = new BrowserCrawler().crawl(request);

            result.pages()
                    .forEach(
                            page ->
                                    System.out.println(
                                            "depth "
                                                    + page.depth()
                                                    + "  "
                                                    + page.finalUrl()
                                                    + "  \""
                                                    + page.title().orElse("(no title)")
                                                    + "\""));
            System.out.println(
                    "Navigated "
                            + result.statistics().claimedNavigations()
                            + " URLs, "
                            + result.statistics().successfulPages()
                            + " succeeded ("
                            + result.terminationReason()
                            + ")");
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
