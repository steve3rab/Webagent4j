# Crawler

The HTTP crawler (Phase 0.6) is implemented. See [http-crawler.md](http-crawler.md) for the full
guide: `CrawlRequest`/`CrawlResult`/`CrawledPage`, deterministic BFS traversal, URL normalization,
deduplication, host/domain scope policy, redirect and retry handling, response-size protection,
and the documented Phase 0.6 limitations (no JavaScript rendering, no `robots.txt` enforcement yet,
no distributed or high-concurrency crawling).

The browser crawler (Phase 0.7) is also implemented. See [browser-crawler.md](browser-crawler.md)
for the full guide: `BrowserCrawlRequest`/`BrowserCrawlResult`/`BrowserCrawledPage`, JavaScript-
rendered link discovery, session-scoped navigation reusing one `IBrowser` per crawl, bounded
concurrency with a deterministic result order, cancellation, and the documented Phase 0.7
limitations (top-level frames only, no SPA `pushState` tracking, no redirect hop list).

General persistent storage remains future, unimplemented work; nothing in this phase claims
otherwise.
