# Browser Crawler

Phase 0.7: a deterministic, bounded-concurrency crawler that discovers and navigates pages through a
real browser, for content the HTTP crawler ([docs/http-crawler.md](http-crawler.md)) cannot render.

## Purpose

`webagent4j-browser-crawler` reuses existing WebAgent4J abstractions - `webagent4j-browser-api` for
navigation, `webagent4j-wait` for stability, `webagent4j-crawler-api`'s value types (`DiscoveredLink`,
`CrawlDecision`, `IUrlNormalizer`) for the parts that mean the same thing regardless of backend -
rather than looping `page.goto()` and collecting `<a href>` values.

## When to use HTTP Crawler

Cheap, deterministic, sequential, no browser process, no JavaScript rendering. Use it whenever the
content you need is present in the raw HTML response.

## When to use Browser Crawler

The target renders links or content through JavaScript, requires an authenticated session, uses
frames, or performs client-side navigation you still want to follow. More expensive: a real browser
process, bounded concurrency to keep it usable on modest hardware, and a stability wait instead of a
single HTTP round trip.

Neither crawler automatically falls back to the other. The caller chooses explicitly.

## Architecture

```text
IBrowserCrawler.crawl(BrowserCrawlRequest)
        |
        v
BrowserCrawler (only implementation)
        |
        +-- Session (per-call mutable state)
              |
              +-- frontier (FIFO, BFS)         -- single coordinator thread only
              +-- ClaimGate (dedup + maxPages)  -- synchronized, single authoritative gate
              +-- bounded worker pool (maxConcurrency)
              |      |
              |      +-- IPage.navigate() -> PageStabilityWaiter (webagent4j-wait) -> IPage.observe()
              |
              +-- commit (single-threaded): scope + dedup decisions, frontier expansion, result assembly
```

`BrowserCrawler` never imports `com.microsoft.playwright.*` - enforced by
`browserCrawlerRemainsIndependentFromPlaywright` in `ArchitectureTest`.

## Public API

- `IBrowserCrawler` - `BrowserCrawlResult crawl(BrowserCrawlRequest request)`. Deliberately **not**
  `ICrawler` - see [API contract clarifications](#compatibility) below.
- `BrowserCrawlRequest` (+ `Builder`) - immutable, fully validated at `build()`.
- `BrowserCrawlResult`, `BrowserCrawledPage`, `BrowserCrawlFailure`, `BrowserCrawlStatistics`.
- `BrowserCrawlFailureType`, `BrowserCrawlTerminationReason`, `FrameCrawlPolicy`.
- `CancellationToken`.
- `BrowserCrawler` - the sole `IBrowserCrawler` implementation.

Internal engine types (`io.webagent4j.browsercrawler.internal`) are `public` by convention (mirroring
`io.webagent4j.crawler.internal`), not by supported contract.

## Session model

One `IBrowser` instance **is** the crawl session: cookies, storage, and authentication state are
already shared across every page it opens, since that is exactly what one isolated browser context
already provides. `BrowserCrawlRequest.browser()` is required; the crawler creates one page per
worker thread (bounded by `maxConcurrency`) via `browser.newPage()` and always closes those
crawler-owned pages when the crawl ends. It never closes the browser itself unless
`closeBrowserOnCompletion(true)` is set - respecting caller ownership by default. A second,
independent crawl on a *different* `IBrowser` never inherits the first crawl's session state.

To crawl authenticated content: launch and log in a browser yourself, then pass it to
`BrowserCrawlRequest.builder(browser)`. There is no generic login-form automation in this phase.

## URL identities and deduplication

The authoritative identity is the **normalized requested URL**, claimed once via `ClaimGate` before
a task is ever enqueued - single-threaded and race-free by construction (only the coordinator thread
ever claims), independently re-verified for true atomicity by `ClaimGateTest`'s concurrent-claim
tests. The **final committed URL** (after any redirect) is re-checked against scope but is *not* a
second dedup identity: two different requested URLs that redirect to the same final URL are both
still navigated and counted separately. This is a deliberate simplification - documented, not hidden
- since the browser follows redirects opaquely and exposes no intermediate hop list.

## Scope semantics

Scheme (`http`/`https` only) -> host/domain (`sameHostOnly`/`includeSubdomains`/`allowedHosts`, with
a literal `.` domain boundary - `evil-example.com` is never accepted for `example.com`) ->
`includeUrlPatterns`/`excludeUrlPatterns`. Checked twice per page: once against the discovered URL
before navigation, and again against the **final committed URL** after navigation - an out-of-scope
redirect is a `BrowserCrawlFailureType.OUT_OF_SCOPE_REDIRECT`, never silently indexed.

## Link discovery

Links come from `IPage.observe()`'s `Observation.links()` (elements with `ElementRole.LINK`), never
raw HTML regex parsing. The Playwright observation backend already resolves `href` to an absolute
URL (`element.href` semantics) and exposes it as the `href-resolved` attribute; `LinkDiscoverer` only
reads that value (falling back to `URI.resolve()` against the document's own URL if it is ever
absent), so relative/root-relative/protocol-relative/base-href resolution is exactly what the browser
itself already computed - never re-implemented.

## Dynamic DOM

Discovery only happens after the configured stability window is satisfied - a link inserted before
stability completes is part of that observation; the observation snapshot is immutable once taken,
so a mutation afterward belongs to a later, explicit crawl of that page, never an implicit background
update.

## Frames

Only `FrameCrawlPolicy.TOP_LEVEL_ONLY` is implemented. `SAME_ORIGIN_FRAMES` and
`ALL_ACCESSIBLE_FRAMES` are declared (mirroring `TraversalStrategy.DEPTH_FIRST`'s
declared-but-rejected precedent) but rejected at `BrowserCrawlRequest` construction: the current
public browser API (`IPage`/`IFrame`/`IFrameLocator`) can locate *one* frame matching an id/name/
title/URL criterion, but has no operation to enumerate *every* frame on a page - a genuine
"all frames" traversal needs that capability added to `webagent4j-browser-api` first, which is out of
scope for this phase.

## SPA support

Links with a real `href` are followed normally, including when the destination renders via
client-side routing - that is just "navigate to a URL." Generic click-driven exploration of
non-anchor elements is explicitly not implemented (would break determinism - see
[Non-goals](roadmap.md)). `history.pushState()` URL changes with no corresponding document
navigation are not tracked as separate crawl entries in this phase - a documented limitation, not a
silent gap.

## Redirect semantics

Observed only as requested-URL vs. final-committed-URL (`IPage.url()` after `navigate()`). No
intermediate hop list, no HTTP status codes - the current backend-neutral browser API exposes
neither, and none are fabricated. Covers HTTP 30x, JavaScript redirects, and meta-refresh alike,
since all of them simply change the committed URL by the time navigation is observed complete.

## Bounded concurrency

`maxConcurrency` bounds both the worker thread pool size and (via one `IPage` per worker thread,
created lazily and reused) the number of active browser pages - never more than `maxConcurrency`
pages exist at once. Default `1` (conservative, per-hardware-friendly default).

**Frontier expansion, claiming, and result assembly all happen on a single coordinator thread; only
navigation is offloaded to worker threads.** Tasks are submitted to the executor in strict frontier
order; the coordinator always waits on the *oldest* still-outstanding task's `Future` before
committing its outcome - so a faster task finishing before an earlier one just sits in its own
`Future`, ready, while the coordinator still processes results in frontier order. Physical completion
order therefore never changes the logical result order. See `BrowserCrawlerTest
.logicalResultOrderIsUnaffectedByCompletionTimingUnderConcurrency` for a scenario with deliberately
different per-page navigation delays proving this holds.

## Cancellation

`CancellationToken` is a minimal, self-contained primitive (none existed anywhere in the codebase -
audited before adding it) - cooperative, not forceful: an in-flight navigation is never forcibly
aborted (no such operation exists in the backend-neutral browser API). Once observed, no new
navigation is claimed; already-claimed, in-flight navigations are allowed to finish, so
already-discovered results remain part of the deterministic output.

## Deduplication

See [URL identities and deduplication](#url-identities-and-deduplication) above.

## Limits

`maxDepth`, `maxPages`, `maxConcurrency`, `navigationTimeout`, `stabilityWindow` - every one
validated at `BrowserCrawlRequest.Builder.build()`, never discovered invalid mid-crawl.

## Failure model

`BrowserCrawlFailureType`: `NAVIGATION_TIMEOUT`, `NAVIGATION_FAILED`, `PAGE_STABILITY_TIMEOUT`,
`OUT_OF_SCOPE_REDIRECT`, `FRAME_ACCESS_FAILED` (unreachable until frame crawling is implemented -
see [Frames](#frames)), `PAGE_CLOSED`, `BROWSER_BACKEND_FAILURE`, `CANCELLED`. A separate taxonomy
from `CrawlFailureType` ({webagent4j-crawler-api}) - most of that one is HTTP-response-shaped
(status codes, redirect hop counts) with no honest browser equivalent. `failFast` stops claiming new
navigations after a fatal failure (cancellation is never treated as fatal); already-in-flight
navigations still complete and commit.

## Determinism contract

Given identical `BrowserCrawlRequest`, deterministic seed/link discovery order, and deterministic
scope/dedup decisions, WebAgent4J guarantees deterministic logical: seed ordering, frontier ordering,
normalized identities, dedup decisions, depth assignment, scope decisions, **page result ordering**
(even under concurrency - see [Bounded concurrency](#bounded-concurrency)), failure ordering, link
discovery ordering (document order), statistics, and termination reason (with explicit precedence:
`CANCELLED` > `FAIL_FAST` > `MAX_PAGES_REACHED` > `COMPLETED`).

**Not** guaranteed deterministic: wall-clock duration, browser scheduling/JavaScript execution
timing, or `Throwable` identity in a failure's `cause()` - the same exclusions the HTTP crawler's own
determinism contract documents, for the same reason.

## Resource ownership

Crawler-owned pages (created via `browser.newPage()`) are always closed when the crawl ends -
success, failure, `failFast`, or cancellation - verified by `BrowserCrawlerTest
.crawlerOwnedPagesAreAlwaysClosed`. The caller-supplied `IBrowser` is never closed unless
`closeBrowserOnCompletion(true)` is explicitly set.

## Examples

```java
try (IBrowser browser = WebAgent.browser().playwright().chromium().headless(true).launch()) {
    BrowserCrawlRequest request =
            BrowserCrawlRequest.builder(browser)
                    .seed("https://example.com/")
                    .maxDepth(2)
                    .maxPages(50)
                    .maxConcurrency(4)
                    .build();
    BrowserCrawlResult result = new BrowserCrawler().crawl(request);
    result.pages().forEach(page -> System.out.println(page.finalUrl()));
}
```

See `webagent4j-examples` for runnable `BrowserCrawlSimpleExample` and
`BrowserCrawlSessionExample`.

## Current limitations

- Only `FrameCrawlPolicy.TOP_LEVEL_ONLY` is implemented - see [Frames](#frames).
- `history.pushState()`-only SPA transitions are not tracked - see [SPA support](#spa-support).
- No intermediate redirect hop list - see [Redirect semantics](#redirect-semantics).
- Two different requested URLs redirecting to the same final URL are both navigated - see
  [URL identities](#url-identities-and-deduplication).
- No download detection: if navigation triggers a browser download instead of a rendered document,
  behavior is backend-defined - `IPage` exposes no content-type/download signal to detect this.
- No robustness-tests-module adversarial suite was added in this phase (unlike Phase 0.6's
  `HttpCrawlerRobustnessIT`) - the integration suite (`BrowserCrawlerIT`) and the unit suite
  (`BrowserCrawlerTest`, including the concurrency-determinism and resource-leak scenarios) cover the
  critical invariants, but not the full adversarial scenario matrix. A follow-up PR is the right size
  for that, rather than growing this one further.
- No CLI `crawl-browser` command was added - out of scope per this phase's own instructions unless it
  fits cleanly, and a real browser process/session argument does not fit the existing CLI's
  argument model without its own design pass.

## Compatibility

No existing Phase 0.1-0.6 public API changed. `IBrowserCrawler` is a new, separate contract from
`ICrawler` - both `ICrawler#crawl(CrawlRequest)` and `ICrawlScopePolicy#evaluate(URI, URI,
CrawlRequest)` are bound to the concrete, HTTP-shaped `CrawlRequest` record, so `BrowserCrawler`
cannot implement `ICrawler` without forcing browser-specific configuration into fields that do not
fit it - exactly what this phase's own design constraints forbid. This mirrors `HttpCrawler`/
`ICrawler`: two clear implementations for two genuinely different backends, not one over-unified
abstraction.
