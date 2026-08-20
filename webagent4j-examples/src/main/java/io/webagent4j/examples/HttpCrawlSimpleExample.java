package io.webagent4j.examples;

import io.webagent4j.crawler.HttpCrawler;
import io.webagent4j.crawler.api.CrawlRequest;
import io.webagent4j.crawler.api.CrawlResult;

/**
 * Demonstrates the simplest HTTP crawl: a bounded, same-host breadth-first crawl with no browser,
 * no JavaScript rendering, and no AI - just {@link HttpCrawler} following {@code <a href>} links.
 */
public final class HttpCrawlSimpleExample {

    private HttpCrawlSimpleExample() {}

    /** Crawls the given seed URL up to depth 2, printing every page's depth, URL, and title. */
    public static void main(String[] args) {
        String seedUrl = requireArgument(args, "seed URL");
        CrawlRequest request =
                CrawlRequest.builder().seed(seedUrl).maxDepth(2).maxPages(20).build();

        CrawlResult result = new HttpCrawler().crawl(request);

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
                "Fetched "
                        + result.statistics().fetchedUrls()
                        + " URLs, "
                        + result.statistics().successfulPages()
                        + " succeeded ("
                        + result.terminationReason()
                        + ")");
    }

    private static String requireArgument(String[] args, String description) {
        if (args.length == 0 || args[0].isBlank()) {
            throw new IllegalArgumentException(
                    "Expected " + description + " as the first argument");
        }
        return args[0];
    }
}
