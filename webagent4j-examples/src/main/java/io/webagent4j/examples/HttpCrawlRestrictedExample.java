package io.webagent4j.examples;

import io.webagent4j.crawler.HttpCrawler;
import io.webagent4j.crawler.api.CrawlRequest;
import io.webagent4j.crawler.api.CrawlResult;

/**
 * Demonstrates scope restriction: {@code sameHostOnly}, a path exclusion pattern, and a {@code
 * maxPages} bound - then prints every rejected link alongside the {@link
 * io.webagent4j.crawler.api.CrawlDecisionType} that explains why it was never fetched.
 */
public final class HttpCrawlRestrictedExample {

    private HttpCrawlRestrictedExample() {}

    /** Crawls the given seed URL, staying on its host and skipping any {@code /admin/} path. */
    public static void main(String[] args) {
        String seedUrl = requireArgument(args, "seed URL");
        CrawlRequest request =
                CrawlRequest.builder()
                        .seed(seedUrl)
                        .sameHostOnly(true)
                        .includeSubdomains(false)
                        .maxDepth(2)
                        .maxPages(15)
                        .excludeUrlPattern(".*/admin/.*")
                        .build();

        CrawlResult result = new HttpCrawler().crawl(request);

        System.out.println("Fetched pages:");
        result.pages().forEach(page -> System.out.println("  " + page.finalUrl()));

        System.out.println("Rejected links:");
        result.rejectedUrls()
                .forEach(
                        link ->
                                System.out.println(
                                        "  "
                                                + link.resolvedUrl()
                                                + "  "
                                                + link.rejection()
                                                        .map(
                                                                decision ->
                                                                        decision.type()
                                                                                + ": "
                                                                                + decision.reason())
                                                        .orElse("(allowed)")));
    }

    private static String requireArgument(String[] args, String description) {
        if (args.length == 0 || args[0].isBlank()) {
            throw new IllegalArgumentException(
                    "Expected " + description + " as the first argument");
        }
        return args[0];
    }
}
