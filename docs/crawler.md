# Crawlers

WebAgent4J provides two intentionally separate crawler contracts.

## HTTP crawler

Use [HTTP crawler](http-crawler.md) when the content you need is present in HTTP responses and JavaScript rendering is unnecessary. It is sequential, deterministic BFS and does not launch a browser.

## Browser crawler

Use [Browser crawler](browser-crawler.md) when links/content require JavaScript rendering or an existing browser session. It is single-lane and uses one caller-supplied `IBrowser` session.

The browser crawler is not an `ICrawler` implementation and does not reuse `CrawlRequest`/`CrawlResult`. HTTP status/redirect/body semantics and browser navigation/stability/observation semantics are different enough that forcing one universal result model would hide important failure information.

Neither crawler automatically falls back to the other. The caller chooses the correct vertical explicitly.

Both crawlers share the network/security responsibilities in [Security model](security-model.md#network-and-ssrf-boundary): `robots.txt` is not enforced and configured host/scheme policy is not a universal SSRF firewall.
