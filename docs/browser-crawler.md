# Browser crawler

The browser crawler follows rendered-page links through a real caller-supplied browser session. Use it when the HTTP crawler cannot see JavaScript-rendered content or when authenticated session state is required.

## Core model

```text
BrowserCrawlRequest
      |
      v
BrowserCrawler
      |
 single caller thread
      |
 one lazily-created page reused across the crawl
      |
 navigation -> stability -> observation -> link decisions
      |
BrowserCrawlResult
```

It does not depend directly on Playwright; it navigates through backend-neutral `IBrowser`/`IPage` contracts.

## Session ownership

The request supplies an `IBrowser`. Its cookies/storage/authentication state define the crawl session. Reusing the same browser across crawls shares that state; separate browsers provide separate sessions.

The crawler creates one page lazily, reuses it sequentially, and closes that page on all terminal paths. The caller's browser remains open by default and is closed only when `closeBrowserOnCompletion(true)` explicitly transfers that responsibility.

## Single-lane execution

The current contract is intentionally one navigation lane. `maxConcurrency` is one. Neither `IBrowser` nor `IPage` provides the thread-safety contract required to justify hidden physical navigation concurrency while preserving deterministic order.

## Identity and deduplication

The authoritative task identity is the normalized requested URL claimed before enqueue. The final committed URL after browser navigation is scope-checked but is not a second transparent dedup identity; two different requested URLs may legitimately navigate even when they end at the same final URL.

Browser redirects are opaque compared with raw HTTP and no intermediate redirect-hop list is promised.

## Scope

Scheme/host/subdomain/allowed-host/pattern policy is evaluated before navigation and against the final committed URL. An out-of-scope final redirect is a structured failure rather than silently indexed content.

This scope policy is not a general SSRF firewall; the caller must authorize the browser's reachable network destinations.

## Link discovery

Links come from bounded semantic observation rather than regex over HTML. Resolved browser `href` values are used where available. Source provenance distinguishes anchor and image-map-area links when supported.

An observation truncated at the element bound is not treated as a complete page discovery: no links from an incomplete snapshot are silently accepted as a complete crawl graph.

## Navigation/stability timeout

Navigation and the subsequent page-stability wait share one monotonic `navigationTimeout` budget. The adapter uses timeout-aware backend operations where exposed.

Once stability is accepted, page URL, semantic observation, and title are separate calls. They are not one atomic snapshot and currently do not share one additional backend-native deadline. This is an explicit limitation rather than an implied guarantee.

## Stability heuristic

Page stability is a bounded DOM-shape/link-target heuristic executed through the backend's timeout-aware wait primitive. It observes ready state, element/link counts, and a bounded link-target digest. It is not network-idle, not a complete content-change detector, and not proof that every asynchronous application task finished.

## Cancellation

Browser crawling has an explicit cooperative cancellation token because the frontier has a natural cancellation boundary. Cancellation is not generalized to actions/workflows automatically because those domains have different side-effect semantics.

Cancellation/fail-fast cleanup still closes the crawler-owned page. Caller-owned browser cleanup follows the explicit ownership option.

## Failures

Navigation timeout, stability timeout, scope redirect, observation truncation, backend failure, cancellation, and other browser-specific outcomes use the browser-crawler taxonomy. Failures are not squeezed into HTTP status semantics.

## Current exclusions

- no physical navigation concurrency;
- no generic click-driven SPA exploration;
- no `history.pushState()`-only crawl entries;
- no recursive frame crawling policy beyond supported top-level behavior;
- no intermediate redirect-hop chain;
- no `robots.txt` engine;
- no universal SSRF protection;
- no automatic HTTP-crawler fallback.
