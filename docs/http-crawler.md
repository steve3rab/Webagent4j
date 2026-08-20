# HTTP crawler

WebAgent4J's HTTP crawler explores a site without a browser: no Playwright, no JavaScript
rendering, no AI. It is a deterministic, sequential, backend-neutral engine built for the cases
where a browser is unnecessary cost - link discovery, site mapping, and bulk HTML retrieval - and
it is designed as reusable infrastructure a future browser crawler (0.7) can build on without ever
coupling to this HTTP-specific implementation.

```text
CrawlRequest -> HttpCrawler -> CrawlResult
                    |
      seed URLs -> frontier (BFS) -> IHttpFetcher -> response classification
                    |                                        |
              URL normalization                    redirect handling / retry
                    |                                        |
              scope policy (host/domain/scheme)    HTML parsing (jsoup) -> links
                    |                                        |
              deduplication (normalized identity) <----------+
                    |
              CrawledPage / CrawlFailure -> CrawlResult
```

Same input, same HTTP responses, same `CrawlRequest` always produce the same logical crawl order
and the same `CrawlResult` - the engine is intentionally sequential (no concurrency) for this
phase, precisely so that guarantee holds without a benchmark-only carve-out.

## Modules

- `webagent4j-crawler-api` (backend-neutral contracts, depends only on `webagent4j-common`):
  `CrawlRequest`, `CrawlResult`, `CrawledPage`, `CrawlFailure`, `CrawlStatistics`,
  `DiscoveredLink`, `CrawlDecision`/`CrawlDecisionType`, `RedirectHop`, `QueryParameterPolicy`,
  `TraversalStrategy`, `LinkKind`, `CrawlTerminationReason`, `CrawlFailureType`, and the
  `ICrawler`/`IUrlNormalizer`/`ICrawlScopePolicy`/`ICrawlDeduplicator` ports.
- `webagent4j-crawler` (the engine): `HttpCrawler`, `JavaHttpFetcher` (a `java.net.http.HttpClient`
  adapter), `JsoupHtmlLinkExtractor`, `DefaultUrlNormalizer`, `HostScopePolicy`, and the internal
  frontier/deduplicator/response-classifier. No Playwright dependency anywhere in either module -
  enforced by dedicated ArchUnit rules (`crawlerRemainsIndependentFromPlaywright`,
  `crawlerRemainsIndependentFromAiLibraries`, `crawlerApiRemainsIndependentFromTheCrawlerEngineModule`).

`webagent4j-crawler` was previously a reserved, empty module (see [modules.md](modules.md)). This
phase graduates it with an entirely different dependency set than its old reservation
(`webagent4j-http` + `webagent4j-storage`, both of which remain reserved and untouched): the
engine lives directly in `webagent4j-crawler` rather than a separate transport module, since
nothing else in the project currently needs a standalone HTTP transport boundary.

## Quick start

```java
CrawlRequest request = CrawlRequest.builder()
        .seed("https://example.com/")
        .maxDepth(2)
        .maxPages(50)
        .build();

CrawlResult result = new HttpCrawler().crawl(request);

for (CrawledPage page : result.pages()) {
    System.out.println(page.depth() + "  " + page.finalUrl() + "  " + page.title());
}
```

`ICrawler#crawl(CrawlRequest)` is the entire public surface: no `CompletableFuture`, no callback,
no cancellation token in this phase - a call returns once the crawl has fully terminated. See
[`webagent4j-examples`](../webagent4j-examples/src/main/java/io/webagent4j/examples) for
`HttpCrawlSimpleExample`, `HttpCrawlRestrictedExample`, and `HttpCrawlDiagnosticsExample`.

## CrawlRequest

Immutable and fully validated at construction - every misconfiguration (`maxDepth < 0`,
`maxPages <= 0`, a non-positive timeout or `maxResponseBytes`, a negative `maxRedirects`, a
non-HTTP(S) seed, `TraversalStrategy.DEPTH_FIRST`) raises `IllegalArgumentException` immediately;
no configuration error surfaces only mid-crawl.

| Field | Default | Notes |
|---|---|---|
| `seeds` | (required) | One or more absolute `http(s)` URLs, in insertion order |
| `maxDepth` | 3 | Seed = depth 0 |
| `maxPages` | 100 | Bounds unique URLs *claimed* (see [Deduplication](#deduplication)) |
| `sameHostOnly` | `true` | Each seed is its own allowed-host root |
| `includeSubdomains` | `false` | True subdomains only, never a lookalike-domain match |
| `allowedSchemes` | `http`, `https` | `mailto:`, `javascript:`, `data:`, etc. never enter the frontier |
| `requestTimeout` | 10s | Per fetch attempt |
| `maxResponseBytes` | 5,000,000 | Enforced while streaming, never after full buffering |
| `maxRedirects` | 5 | Per fetch attempt (one task, many hops) |
| `retryPolicy` | `RetryPolicy.defaults()` | Reused from `webagent4j-common`; 3 attempts, 100ms/2x backoff |
| `retryableStatusCodes` | 429, 500, 502, 503, 504 | A 4xx is never retried by default |
| `userAgent` | `WebAgent4J-Crawler/0.1` | Never impersonates a browser |
| `defaultHeaders` | (none) | `Cookie`/`Authorization` are never added automatically |
| `allowedContentTypes` | `text/html`, `application/xhtml+xml` | Anything else is skipped, not parsed |
| `queryParameterPolicy` | `KEEP_ALL` | See [URL normalization](#url-normalization) |
| `includeUrlPatterns` / `excludeUrlPatterns` | (none) | Regex allow/deny lists |
| `failFast` | `false` | One page's failure stops the whole crawl if `true` |

## Crawler public API

`ICrawler { CrawlResult crawl(CrawlRequest request); }` is deliberately minimal so a future
`BrowserCrawler` (0.7) could implement the same interface if its semantics turn out to be
genuinely compatible - this phase does not force that unification, and does not create a
`BrowserCrawler`. `HttpCrawler implements ICrawler`, with a no-arg constructor for production use
and a fully-injected constructor (`IHttpFetcher`, `IHtmlLinkExtractor`, `ICrawlScopePolicy`,
`IWaitSleeper`) that tests use to avoid the network and real sleeping.

## CrawledPage

Immutable, with every exposed collection defensively copied. `requestedUrl` and `finalUrl` are
tracked separately, since a redirect chain can change identity; `depth`, `discoveredFrom`,
`statusCode`, `headers`, `contentType`, `charset`, `html`, `title`, `declaredCanonicalUrl`,
`links` (every link found, in document order, allowed and rejected alike), `redirectChain`,
`responseBytes`, `fetchDuration`, and `provenance` are all part of the immutable record.
`declaredCanonicalUrl` is the page's own `<link rel="canonical">`, observed only - it is never
substituted for crawl identity, since a canonical link can be wrong, cross-domain, or deliberately
different from the URL that was actually crawled.

`CrawledPage` deliberately contains no `java.net.http.HttpClient` (or any other backend) type,
staying useful as a target data shape for a future browser crawler without forcing early
unification.

## CrawlResult

Immutable: `pages()` (in deterministic crawl order), `failures()`, `statistics()`,
`rejectedUrls()` (every link the crawl considered but did not follow - out-of-scope links and
duplicate links alike, each carrying the `CrawlDecision` that explains why), and
`terminationReason()` - `COMPLETED`, `MAX_PAGES_REACHED`, or `FATAL_ERROR` (only when `failFast`
is enabled). `maxDepth` truncating the frontier is not, by itself, a distinct termination reason -
a crawl that simply runs out of in-scope, in-depth URLs to fetch is `COMPLETED`.

## URL normalization

`DefaultUrlNormalizer` is deterministic and idempotent
(`normalize(normalize(u)) == normalize(u)`, proven by a parameterized test): lowercases scheme and
host, drops the fragment, drops the default port (80 for `http`, 443 for `https`), resolves `.`/
`..` segments, maps an empty path to `/`, and leaves an explicit non-empty trailing slash
(`/products/`) unchanged - many servers treat it as a different resource, so the normalizer never
guesses. Already-percent-encoded paths are never re-encoded or corrupted: the normalized URI is
rebuilt by string concatenation of already-encoded components, then parsed with the single-arg
`URI(String)` constructor, never the multi-arg constructor (which would re-encode).

The normalizer never upgrades `http` to `https`, never drops query parameters by arbitrary
heuristic, and never applies a probabilistic "looks like a duplicate" rule.

### Query parameter policy

`QueryParameterPolicy` is one of `KEEP_ALL` (default), `DROP_ALL`, or `DROP_KNOWN_TRACKING` -
which is deliberately conservative: it only ever drops `utm_source`, `utm_medium`, `utm_campaign`,
`utm_term`, and `utm_content`. `includeQueryParameter`/`excludeQueryParameter` let a caller adjust
either mode without reaching for a larger DSL.

## Frontier strategy

`ICrawlFrontier`'s only implementation this phase, `BreadthFirstCrawlFrontier`, is a plain FIFO
queue. A plain FIFO queue is already correct breadth-first order here, because a page's children
are only discovered while that page's own task is being processed, and are always appended after
every already-queued same-depth peer - no separate per-depth bucketing is needed. `HashSet`/
`HashMap` iteration order never leaks into crawl order anywhere in the engine.
`TraversalStrategy.DEPTH_FIRST` is a recognized enum value reserved for a future phase; it is
rejected at `CrawlRequest` construction time, never silently downgraded to breadth-first.

## Deduplication

Identity is the *normalized* URL. `InMemoryCrawlDeduplicator#tryClaim(URI)` returns `true` only
the first time a given normalized identity is seen in one crawl - `/products`, `/products#top`,
and `/a/../products` all collapse to the same identity and are fetched at most once.

`maxPages` bounds the number of URLs *claimed* (proactively checked immediately before every
`tryClaim` call, for both seeds and discovered links), not the number of *successful* pages - a
site that is mostly 404s cannot bypass the limit by failing. This guarantees
`fetchedUrls <= maxPages` structurally, and the frontier never accumulates unreachable stragglers
past the limit.

Duplicate rejections are recorded twice: once in `CrawlStatistics#duplicateUrls()`, and once as a
`REJECT_DUPLICATE` entry in `CrawlResult#rejectedUrls()` - the aggregate list carries every
non-followed link, scope rejections and duplicate rejections alike, so `rejectedUrls().size()`
and `CrawlStatistics#rejectedUrls()` always agree.

## Scope/domain policy

`HostScopePolicy#evaluate(candidate, source, request)` returns a `CrawlDecision` richer than a
boolean - `ALLOW`, or a `REJECT_*` reason (`REJECT_SCHEME`, `REJECT_HOST`, `REJECT_DOMAIN`,
`REJECT_URL_FILTER`, `REJECT_DEPTH`, `REJECT_MAX_PAGES`, `REJECT_DUPLICATE`) with a human-readable
explanation, checked in order: scheme, then host/domain, then include/exclude URL patterns.

`sameHostOnly(true)` requires an exact host match against a seed's host (or an entry in
`allowedHosts`). `includeSubdomains(true)` additionally allows a *true* subdomain -
`www.example.com` for `example.com` - checked with a dot-boundary-aware suffix match
(`host.endsWith("." + root)`), never a bare `endsWith(root)`, which would wrongly also accept a
lookalike host such as `evil-example.com`. With multiple seeds, each seed establishes its own
independent allowed-host root - a link back to `b.test` is allowed if `b.test` was itself a seed,
even while crawling from `a.test`, never silently restricted to only the first seed's host.

No Public Suffix List is used in this phase; "domain" scoping is a same-registrable-domain
heuristic only insofar as `includeSubdomains` compares against the literal seed/allowed hosts, not
a real registrable-domain computation. See [Known limitations](limitations.md).

## HTTP fetcher

`IHttpFetcher#fetch(HttpFetchRequest)` performs one `GET` round trip and returns a structured
`HttpFetchResult` (status code, headers, body, content type, elapsed time) - a 404 or 500 is a
normal result, never a thrown exception; only a genuine transport failure (timeout, I/O error,
oversized response) throws. `HttpCrawler` depends only on this interface, never directly on
`java.net.http.HttpClient`, so tests inject a fake fetcher without any real network access.

`JavaHttpFetcher` is the production implementation. It sends no `Accept-Encoding` header, so a
well-behaved server responds uncompressed - this phase does not implement gzip/deflate decoding,
and `responseBytes()` is therefore unambiguously the decoded content length.

## Redirect handling

`JavaHttpFetcher` is built with `HttpClient.Redirect.NEVER`; `HttpCrawler` follows each redirect
hop itself, one at a time, and checks every redirect target against the scope policy *before*
following it - scope control stays under WebAgent4J's responsibility, never an opaque HttpClient
auto-follow. A redirect to an out-of-scope host (for example, external while `sameHostOnly` is
`true`) is never fetched; it fails as `INVALID_REDIRECT` with the scope decision's reason.

A redirect chain revisiting a URL already seen earlier in the *same* chain fails as
`REDIRECT_LOOP`. A chain longer than `maxRedirects` fails as `TOO_MANY_REDIRECTS`; a chain of
exactly `maxRedirects` hops succeeds. `CrawledPage` exposes `requestedUrl`, `finalUrl`, and the
full `redirectChain()` (`RedirectHop(from, to, statusCode)` per hop).

## Retry behavior

`retryPolicy` (an `io.webagent4j.common.RetryPolicy`, reused directly rather than a
crawler-specific type) governs attempt count and exponential backoff for
`retryableStatusCodes` and, if `retryOnIoException` is `true`, network/IO failures. Backoff sleeps
go through the injected `IWaitSleeper` (the same abstraction `webagent4j-wait`'s `WaitEngine`
uses) - never `Thread.sleep` directly - so a test can inject a recording or no-op sleeper and stay
deterministic. A 4xx status is never retried by default; only the configured retryable codes are.

## Failure taxonomy

Every `CrawlFailure` carries a `CrawlFailureType`, a message, an optional status code, an optional
cause, an attempt count, and the discovering URL. Types: `NETWORK`, `TIMEOUT`,
`INVALID_REDIRECT`, `REDIRECT_LOOP`, `TOO_MANY_REDIRECTS`, `HTTP_CLIENT_ERROR` (terminal 4xx),
`HTTP_SERVER_ERROR` (5xx that exhausted its retry budget), `RESPONSE_TOO_LARGE`,
`UNSUPPORTED_CONTENT_TYPE`, `INVALID_CONTENT`, `INVALID_URL`, and `BACKEND_FAILURE` - an opaque,
unexpected fetcher exception, preserving WebAgent4J's fail-closed philosophy from earlier phases:
an unexpected backend failure never silently becomes an empty page, a skipped link, a fabricated
`404`, or an unsignaled partial success.

By default (`failFast = false`) one page's failure is recorded and the crawl continues - a page
failing with a 500 does not stop its siblings from being fetched. With `failFast = true`, the
first failure of *any* type (including `BACKEND_FAILURE`) stops the crawl with
`CrawlTerminationReason.FATAL_ERROR`.

## Response-size protection

`maxResponseBytes` is enforced while the response body streams in, never after it has been fully
buffered: `JavaHttpFetcher`'s bounded body subscriber tracks bytes received and cancels the
subscription the instant one more chunk would exceed the limit, raising
`ResponseTooLargeException` (surfaced as `CrawlFailureType.RESPONSE_TOO_LARGE`) - never an
`OutOfMemoryError`, and never a partial page silently treated as success.

## HTML/link extraction

`JsoupHtmlLinkExtractor` uses jsoup - a real, tolerant HTML parser, never a regular expression -
to extract `<a href>` and `<area href>` links (never `script src`, `img src`, `link
stylesheet href`, or `iframe src`; those are resource references, not navigation links, and are
out of scope for this phase), the document `<title>`, and a declared `<link rel="canonical">`.
Malformed markup (unclosed tags, uppercase tags, unquoted attribute values) does not fail the
whole page; an individual unparsable `href` is skipped without aborting extraction of the rest.

A relative href is resolved against a valid `<base href>` when present (jsoup tracks the
effective base URI as it parses, so no manual base-href resolution code is needed), falling back
to the page's own final URL if `<base href>` is missing or invalid. Relative, absolute,
root-relative, protocol-relative (`//host/path`), query-only, and fragment-only hrefs are all
handled; an empty `href=""` resolves to the current page itself, per RFC 3986 - not skipped as if
malformed.

Charset detection order: the `Content-Type` header's `charset` parameter, then a byte-order mark
(UTF-8/UTF-16LE/UTF-16BE), then a UTF-8 fallback. HTML `<meta charset>` sniffing is deliberately
not implemented in this phase, favoring one simple, fully deterministic rule over a heavier one.

## Provenance

`CrawlPageProvenance` (seed origin, `discoveredFrom`, depth, requested URL, final URL, redirect
chain) lets a caller reconstruct seed -> page A -> page B -> the current page without WebAgent4J
building or storing a general discovery graph.

## Limitations explicit to this phase

No JavaScript execution, SPA navigation, or dynamic DOM; no clicks, forms, or infinite-scroll
handling; no browser cookies or session state (the crawler is stateless between requests); no
visual/computer-vision extraction; no automatic browser fallback; no distributed or
high-concurrency crawling (this phase is intentionally sequential); no sitemap orchestration; no
workflow engine; no AI-based ranking or extraction; no MCP/agent tooling. `robots.txt` is not
enforced in this phase - `ICrawlScopePolicy` is the extension point a future phase would use to
add it, and this document does not claim compliance it does not implement. See
[Known limitations](limitations.md) for the full, project-wide list.

## Relation to the browser crawler (0.7)

A future browser crawler could reuse `CrawlRequest`'s concepts, `CrawlResult`'s shape, URL
normalization, the scope policy, and deduplication - but it would fetch and render through a real
browser. No `BrowserCrawler` exists yet; this phase does not begin that work.
