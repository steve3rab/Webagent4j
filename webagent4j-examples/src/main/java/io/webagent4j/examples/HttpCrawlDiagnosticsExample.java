package io.webagent4j.examples;

import io.webagent4j.crawler.HttpCrawler;
import io.webagent4j.crawler.api.CrawlRequest;
import io.webagent4j.crawler.api.CrawlResult;
import io.webagent4j.crawler.api.CrawlStatistics;

/**
 * Demonstrates full crawl diagnostics: every {@link CrawlStatistics} field, plus each structured
 * {@link io.webagent4j.crawler.api.CrawlFailure} - the crawler's fail-closed contract means an
 * unexpected backend problem always surfaces here, never as a silently missing page.
 */
public final class HttpCrawlDiagnosticsExample {

    private HttpCrawlDiagnosticsExample() {}

    /** Crawls the given seed URL and prints every statistic and structured failure. */
    public static void main(String[] args) {
        String seedUrl = requireArgument(args, "seed URL");
        CrawlRequest request =
                CrawlRequest.builder().seed(seedUrl).maxDepth(2).maxPages(30).build();

        CrawlResult result = new HttpCrawler().crawl(request);
        CrawlStatistics stats = result.statistics();

        System.out.println("terminationReason:  " + result.terminationReason());
        System.out.println("discoveredUrls:     " + stats.discoveredUrls());
        System.out.println("fetchedUrls:        " + stats.fetchedUrls());
        System.out.println("successfulPages:    " + stats.successfulPages());
        System.out.println("failedUrls:         " + stats.failedUrls());
        System.out.println("rejectedUrls:       " + stats.rejectedUrls());
        System.out.println("redirects:          " + stats.redirects());
        System.out.println("duplicateUrls:      " + stats.duplicateUrls());
        System.out.println("totalBytes:         " + stats.totalBytes());
        System.out.println("maxDepthReached:    " + stats.maxDepthReached());

        System.out.println("Failures:");
        result.failures()
                .forEach(
                        failure ->
                                System.out.println(
                                        "  "
                                                + failure.failedUrl()
                                                + "  ["
                                                + failure.type()
                                                + "]  "
                                                + failure.message()
                                                + failure.statusCode()
                                                        .map(code -> " (HTTP " + code + ")")
                                                        .orElse("")));
    }

    private static String requireArgument(String[] args, String description) {
        if (args.length == 0 || args[0].isBlank()) {
            throw new IllegalArgumentException(
                    "Expected " + description + " as the first argument");
        }
        return args[0];
    }
}
