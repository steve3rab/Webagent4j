# Crawler

The HTTP crawler (Phase 0.6) is implemented. See [http-crawler.md](http-crawler.md) for the full
guide: `CrawlRequest`/`CrawlResult`/`CrawledPage`, deterministic BFS traversal, URL normalization,
deduplication, host/domain scope policy, redirect and retry handling, response-size protection,
and the documented Phase 0.6 limitations (no JavaScript rendering, no `robots.txt` enforcement yet,
no distributed or high-concurrency crawling).

A browser-based crawler (Phase 0.7) and general persistent storage remain future, unimplemented
work; nothing in this phase claims otherwise.
