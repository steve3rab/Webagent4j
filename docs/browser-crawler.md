# Browser Crawler

Phase 0.7: a deterministic, single-lane crawler that discovers and navigates pages through a real
browser, for content the HTTP crawler ([docs/http-crawler.md](http-crawler.md)) cannot render.

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
process, single-lane navigation (see [Concurrency model](#concurrency-model)), and a stability wait
instead of a single HTTP round trip.

Neither crawler automatically falls back to the other. The caller chooses explicitly.

## Architecture

```text
IBrowserCrawler.crawl(BrowserCrawlRequest)
        |
        v
BrowserCrawler (only implementation)
        |
        +-- Session (per-call mutable state, entirely single-threaded)
              |
              +-- frontier (FIFO, BFS)
              +-- ClaimGate (dedup + maxPages)
              +-- one IPage, created lazily, reused for every navigation this crawl makes
              |      |
              |      +-- IPage.navigate() -> PageStabilityWaiter (webagent4j-wait) -> IPage.observe()
              |
              +-- commit: scope + dedup decisions, frontier expansion, result assembly
```

Every step - frontier expansion, claiming, navigation, and result assembly alike - runs on the one
thread that calls `crawl(...)`. See [Concurrency model](#concurrency-model) for why.

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

Two additive, backward-compatible extensions to existing Phase 0.1-0.6 public types were required to
make `navigationTimeout` genuinely authoritative and to support the cancellation invariant above -
see [Compatibility](#compatibility) for the full, precise statement:

- `IPage` (`webagent4j-browser-api`) gained a default method, `navigate(String, Duration)`, and a new
  type in the same package, `NavigationTimeoutException` - see [Navigation timeout](#navigation-timeout).
- `CrawlDecisionType` (`webagent4j-crawler-api`) gained one new enum constant, `REJECT_CANCELLED` -
  see [Cancellation](#cancellation).

No existing method signature changed and no existing type was removed.

## Session model

The caller-supplied `IBrowser` instance **is** the crawl's session boundary - `BrowserCrawler` does
not create or guarantee any isolation of its own. Cookies, storage, and authentication state already
reachable through that `IBrowser` are shared across every page this crawl opens, exactly as they
would be for any other caller of the same instance. If the caller reuses one `IBrowser` across
several crawls (or alongside other automation on it), those crawls intentionally share that session
state - isolating one crawl's session from another is the caller's responsibility, not something this
engine does for them. `BrowserCrawlRequest.browser()` is required; the crawler creates exactly **one**
page, lazily, on its first navigation, and reuses that same page for every URL the crawl visits -
mirroring how a single browser tab browses from page to page, not one tab per task. That
crawler-owned page is always closed when the crawl ends. The crawler never closes the browser itself
unless `closeBrowserOnCompletion(true)` is set - respecting caller ownership by default. Two crawls
each given their *own*, separately-launched `IBrowser` never share session state with each other.
See [Concurrency model](#concurrency-model) for why there is only ever one page, never a pool of
them.

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
raw HTML regex parsing - both `a[href]` and image-map `area[href]` elements are observed and role-
inferred as `LINK`. The Playwright observation backend already resolves `href` to an absolute URL
(`element.href` semantics, which resolves the same way for `<area>` as it does for `<a>`) and
exposes it as the `href-resolved` attribute; `LinkDiscoverer` only reads that value (falling back to
`URI.resolve()` against the document's own URL if it is ever absent), so relative/root-relative/
protocol-relative/base-href resolution is exactly what the browser itself already computed - never
re-implemented. `<area>` elements carry the HTML default UA stylesheet's `display: none`, which would
otherwise make them read as "not visible" and be filtered out of every observation regardless of
whether their image map is actually shown; the Playwright observation backend exempts `<area>` from
that specific check so an image-map link is discoverable the same as any other visible link.

`LinkDiscoverer` maps each element's source tag to `DiscoveredLink#kind()` explicitly -
`<a>` &rarr; `LinkKind.ANCHOR`, `<area>` &rarr; `LinkKind.AREA` - and this provenance is carried
through unchanged, never defaulted to `ANCHOR`, on every decision path (accepted, out-of-scope,
duplicate, max-depth, max-pages, cancelled). A link-role element sourced from neither tag (for
example, an arbitrary element with an explicit `role="link"` and a script-set `href`) is skipped
rather than assigned an invented `LinkKind`. Seeds are the one exception: they never originate from
an HTML element at all, so a rejected seed's `DiscoveredLink` uses `LinkKind.ANCHOR` as a documented
convention, not a provenance claim - see `BrowserCrawler`'s `SEED_LINK_KIND` Javadoc.

## Navigation timeout

`navigationTimeout` is one authoritative, monotonic budget covering both the navigation attempt
itself and the subsequent stability wait - not merely a client-side check performed after a call
that a backend's own default timeout already bounded to something longer. `BrowserCrawler` passes
the remaining budget into `IPage.navigate(String, Duration)` for navigation, and the remaining
budget again into `IPage.waitForCondition(String, Duration)` for stability (see
[Stability](#stability)) - never a plain, backend-default-timed overload for either. Both
overloads' contracts are explicit about what "authoritative" means: a backend that cannot honor a
caller-supplied timeout must say so by throwing `UnsupportedOperationException` rather than
silently applying its own (possibly much longer) default; the Playwright adapter overrides both and
maps the timeout directly onto the native driver's own per-call timeout options
(`Page.NavigateOptions#setTimeout`, `Page.WaitForFunctionOptions#setTimeout`), so both are
genuinely enforced *by the backend itself*, not by a Java-side deadline check wrapped around an
unbounded call. A real navigation timeout surfaces as the backend-neutral
`io.webagent4j.browser.NavigationTimeoutException`; a real stability timeout surfaces as
`io.webagent4j.browser.ConditionTimeoutException` (the Playwright adapter translates its native
`TimeoutError` to each type, respectively). Both propagate with their typed identity intact all the
way into the resulting `BrowserCrawlFailure.cause()` - `BrowserCrawler` classifies them directly to
`NAVIGATION_TIMEOUT` and `PAGE_STABILITY_TIMEOUT` - never inferred from `WaitBudget.expired()`'s
timing or from matching an exception message, and never discarded in favor of a generic message.
The one exception carries no cause by construction, not by loss: if navigation itself already
consumes the entire shared budget, `PageStabilityWaiter` never attempts a backend call for
stability at all - it raises its own `ConditionTimeoutException` (still classified the same way)
rather than pass a sub-millisecond timeout into a backend call that could not honor it either.
Navigation and stability draw from the same budget, so a slow navigation leaves correspondingly
less time for stability, never a fresh full timeout for each stage.

**What `navigationTimeout` does *not* bound:** once stability succeeds, `BrowserCrawler` still
calls `page.url()`, `page.observe(...)`, and `page.title()` to assemble the result - none of these
calls receive any further deadline. This is an honest, current limitation, not an oversight: unlike
navigation and stability, none of `IPage`'s observation-family operations currently has a
timeout-aware, backend-natively-bounded overload, so extending the same bounded-call architecture to
them would be a separate, larger change. In practice the window in which one of these calls could
race a client-side navigation is much narrower than the navigation/stability window it replaced -
they only run after stability has already certified the DOM quiescent - but it is not zero, and it
is not enforced. These three calls are also not one atomic snapshot of browser state: `page.url()`,
`page.observe(...)`, and `page.title()` are separate, sequential calls, so `BrowserCrawledPage`'s
`finalUrl`, `links`, and `title` are not guaranteed to reflect exactly the same instant as each other
or as `timeToStability`'s own deadline (see `BrowserCrawledPage`'s Javadoc). See
[Current limitations](#current-limitations).

## Stability

`PageStabilityWaiter` hands the *entire* stability condition - fingerprint computation, change
detection, and the `stabilityWindow` bookkeeping - to the backend as one JavaScript predicate via
`IPage.waitForCondition(String, Duration)`, rather than polling `IPage.evaluate(String)` from a
Java-side loop. This is a deliberate architecture, not the original one: an earlier version of this
class built a `webagent4j-wait` `WaitPolicy.withStableFor(...)` around a probe that called
`evaluate()` once per poll, and `WaitEngine` only ever checks its budget *between* probe calls - it
cannot interrupt a single `evaluate()` call already in flight. `evaluate()` has no timeout of its
own, so a poll that happened to land during a client-side navigation transition (a meta-refresh, a
JS `location.assign`/`location.replace`, or a router push mid-flight) could block the underlying
call indefinitely - no exception, no timeout, ever, until (if ever) the driver call itself
returned. `navigationTimeout` was, in that design, not actually authoritative over stability the way
this document claimed. There is exactly one call from Java into the backend per stability wait, and
that call, not a loop wrapped around it, is what the backend itself bounds to its native `timeout` -
this is the architectural guarantee the whole design depends on, and it holds regardless of what
happens to the page's execution context while the call is in flight. `IPage`'s `waitForCondition`
contract is deliberately return-value-free (`void`, not the JavaScript predicate's own truthy value)
precisely so no implementation needs a second, independently-unbounded call after its own bounded
wait resolves - the Playwright adapter never calls `JSHandle#jsonValue()` or `JSHandle#dispose()` on
`waitForFunction`'s returned handle for exactly this reason; a call after the bounded one would still
leave "did the whole operation return" unbounded even though the wait itself was bounded. The
predicate tracks its own "stable since" timestamp using the page's own monotonic `performance.now()`,
never wall-clock `Date.now()`.

Separately, and empirically: the Playwright adapter maps `waitForCondition` directly onto
`Page.waitForFunction`, which polls entirely driver-side. In the Playwright version currently pinned
by this project (1.60.0), `BrowserCrawlerRobustnessIT`'s real-Playwright client-side-navigation-
during-stability regression test exercises this call across a client-side navigation (a meta-refresh)
that replaces the page's execution context mid-wait, and it completes rather than throwing "context
destroyed" or hanging. That specific cross-navigation resilience is an observed behavior of the
pinned Playwright version, proven by that test - not a documented, versioned contract of the
Playwright Java API asserted here as a universal guarantee. This design's correctness does not rest
on it: it rests only on the native-timeout guarantee described above.

The stability predicate's fingerprint is four parts: `document.readyState`; the total element count;
the total count of `href`-bearing anchors and image-map areas (`a[href]`, `area[href]` - see
[Link discovery](#link-discovery)); and `JSON.stringify` of a bounded, document-order array of the
first 2000 such links' `href` attribute values. The href digest exists because a link's target can
change without changing the element count at all - an SPA hydration step rewriting `href` attributes
in place, for example - which the element-count signal alone cannot see; it is
`JSON.stringify`-encoded, not delimiter-joined, so an href that itself contains the delimiter cannot
collide with a genuinely different href sequence. **The 2000-link digest cap is an independently
chosen, generous bound - it is *not* guaranteed to be the same set of links a truncated observation
would retain.** `ObservationOptions.maxElements(2000)` caps the first 2000 *semantic elements of any
kind* in document order (headings, buttons, forms, images, and more - not only links), so a link
that is, say, the 1800th link on the page but the 2400th semantic element overall is covered by this
digest yet could still be missing from a truncated observation, and the reverse is possible on a
link-dense page with few other semantic elements. The two bounds happen to share the same number for
consistency, not because one is derived from or provably equal to the other. This is still a
bounded, purely DOM-shape-based heuristic, not a network-idle signal and not a general
content-change detector: an anchor's or area's visible text, or any non-link content changing
without an accompanying element-count or href change, is not detected, and the engine takes exactly
one observation snapshot per navigation - it never continues monitoring the DOM after stability is
accepted (see [Dynamic DOM](#dynamic-dom)).

## Observation truncation

`BrowserCrawler` observes each page through a bounded `ObservationOptions` (`maxElements(2000)`).
If that capture hits the limit - `Observation.statistics().truncated()` is `true` - the page is
never recorded as a complete, successful discovery: it becomes a `BrowserCrawlFailureType
.OBSERVATION_TRUNCATED` failure instead, with a diagnostic message built from `ObservationStatistics
.truncations()` (which limit was hit, how many elements were retained versus how many existed). No
links from a truncated observation are ever claimed or enqueued - an incomplete snapshot could be
missing exactly the links past the retained boundary, so treating it as complete would silently
under-crawl the site. The bound itself is not relaxed to work around this: raising `maxElements`
only moves the same problem to a larger page.

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

Observed only as requested-URL vs. final-committed-URL (`IPage.url()` after `navigate()` and
stability). No intermediate hop list, no HTTP status codes - the current backend-neutral browser API
exposes neither, and none are fabricated. Covers HTTP 30x, JavaScript redirects, and meta-refresh
alike, since all of them simply change the committed URL by the time navigation is observed
complete - an HTTP 30x is resolved by the backend inside `navigate()` itself, while a client-side
redirect (JavaScript `location.assign`/`location.replace`, or a meta-refresh) typically fires after
the first document commits, mid-stability-wait; see [Stability](#stability) for why that transition
is now bounded rather than able to hang the wait that observes it.

## Concurrency model

This engine performs **no physical navigation concurrency**. Every operation - frontier expansion,
claiming, `IBrowser#newPage()`, every `IPage` call, and result assembly - runs on the single thread
that calls `crawl(...)`. `maxConcurrency` must be exactly `1`; any other value is rejected at
`BrowserCrawlRequest.build()`. The field is kept (not removed) so a future phase that can honestly
offer more than one lane does not need a breaking API change to add it.

**This was not the original Phase 0.7 design**, and the change is deliberate, not cosmetic. The
first cut of this engine navigated up to `maxConcurrency` pages at once through a bounded worker-
thread pool, one `IPage` per worker thread (`ThreadLocal`), all created from the same caller-supplied
`IBrowser`. That violated a contract that was already documented, in this exact codebase, before this
phase existed: both [`IBrowser`](../webagent4j-browser-api/src/main/java/io/webagent4j/browser/IBrowser.java)
and [`IPage`](../webagent4j-browser-api/src/main/java/io/webagent4j/browser/IPage.java) are Javadoc'd
as **not thread-safe**, and the concrete Playwright adapter backs that with unsynchronized state
(`PlaywrightBrowser` tracks its pages in a plain `IdentityHashMap`) over one native Playwright
browser/context that the Playwright Java driver itself expects single-threaded access to. Under real
concurrent navigation (`maxConcurrency(3)` against three real pages), this silently corrupted a real
crawl: a page that was correctly discovered simply never appeared in the committed result - caught by
`BrowserCrawlerIT`'s own concurrency test on real CI, not by any mock-based unit test, because the
mocks had no way to reproduce a native-driver-level race.

One caller-supplied `IBrowser` is the crawl session (cookies/storage/auth all live on it - see
[Session model](#session-model)), and WebAgent4J has no supported way to clone or fan a session out
across independent browser instances. Given that, and given neither `IBrowser` nor `IPage` offers a
thread-safety contract to build concurrent navigation on, offering physical concurrency here would
mean either quietly violating that contract again or inventing session-sharing behavior the platform
does not actually support - both rejected. A single execution lane is the only architecture that is
simultaneously correct against real Playwright, honest about what it does, and consistent with the
session guarantee this engine already promises.

Because there is exactly one execution lane, logical determinism (seed order, frontier order, dedup,
depth, scope decisions, discovered-link order, statistics, termination reason) is now structural
rather than merely provable-under-concurrency: there is no second thread whose completion timing
could ever reorder anything. `BrowserCrawlerTest.everyBackendCallHappensOnTheSingleCallingThread`
proves the thread-confinement half of this directly, instrumenting every backend call across a
multi-page crawl and asserting all of them land on the one thread that called `crawl(...)`.

## Cancellation

`CancellationToken` is a minimal, self-contained primitive (none existed anywhere in the codebase -
audited before adding it) - cooperative, not forceful: an in-flight navigation is never forcibly
aborted (no such operation exists in the backend-neutral browser API). Once observed, **no new
navigation identity may be claimed** - checked centrally, before both a seed claim and a discovered
child's claim, so no code path can accidentally claim past a cancellation. A navigation already
claimed and in flight when cancellation is observed is allowed to finish and its result is retained
deterministically, but the links it discovers are rejected (`CrawlDecisionType.REJECT_CANCELLED`,
visible in `rejectedUrls()`) rather than claimed - the frontier stops growing the moment cancellation
is observed, it does not merely stop being drained.

`REJECT_CANCELLED` is a value on the shared `CrawlDecisionType` (`webagent4j-crawler-api`), not a
browser-crawler-local type - a deliberate choice, not the path of least diff. `DiscoveredLink`'s own
invariant requires a rejected link to carry a `CrawlDecision`; representing "cancelled, never
claimed" any other way would mean either fabricating a misleading existing reason (`REJECT_MAX_PAGES`
would falsely imply a capacity limit was hit) or silently omitting these links from
`BrowserCrawledPage.links()` entirely - which would itself be exactly the kind of silent incomplete
result this project's fail-closed philosophy exists to prevent (see
[Observation truncation](#observation-truncation) for the same principle applied elsewhere).
Cancellation-during-discovery is a genuinely engine-neutral crawl decision, not an HTTP- or
browser-specific concept, so it belongs in the shared type both crawlers' `DiscoveredLink` results
already draw from - this is an additive, backward-compatible change to `CrawlDecisionType` (see
[Public API](#public-api)); the HTTP crawler does not use this value today and is otherwise
untouched.

## Deduplication

See [URL identities and deduplication](#url-identities-and-deduplication) above.

## Limits

`maxDepth`, `maxPages`, `maxConcurrency`, `navigationTimeout`, `stabilityWindow` - every one
validated at `BrowserCrawlRequest.Builder.build()`, never discovered invalid mid-crawl.
`navigationTimeout` and `stabilityWindow` must each be a positive, whole-millisecond `Duration` of
at least one millisecond: both are ultimately handed to a backend timeout option resolved in
milliseconds, so a positive value carrying a sub-millisecond remainder (`Duration.ofNanos(1_500_000)`,
1.5ms, for example) can never be honestly honored at that resolution - rejected explicitly rather
than silently truncated or rounded to a duration the caller never asked for. `IPage`'s own
implementations enforce the identical precision contract on the `Duration` values they accept (see
`IPage`'s class-level "Timeout precision" note); the validator itself is a private implementation
detail, not shared public API (see [Public API](#public-api)).
`stabilityWindow` must not exceed `navigationTimeout`: since both draw from one shared budget, a
`stabilityWindow` longer than the whole budget could never be satisfied even by a page that
navigates instantly, so that configuration is rejected at `build()` rather than discovered mid-crawl
as a page that can structurally never succeed.

## Failure model

`BrowserCrawlFailureType`: `NAVIGATION_TIMEOUT`, `NAVIGATION_FAILED`, `PAGE_STABILITY_TIMEOUT`,
`OUT_OF_SCOPE_REDIRECT`, `OBSERVATION_TRUNCATED` (see [Observation truncation](#observation-truncation)),
`FRAME_ACCESS_FAILED` (unreachable until frame crawling is implemented -
see [Frames](#frames)), `PAGE_CLOSED`, `BROWSER_BACKEND_FAILURE`, `CANCELLED`. A separate taxonomy
from `CrawlFailureType` ({webagent4j-crawler-api}) - most of that one is HTTP-response-shaped
(status codes, redirect hop counts) with no honest browser equivalent. `failFast` stops claiming new
navigations after a fatal failure (cancellation is never treated as fatal).

## Determinism contract

Given identical `BrowserCrawlRequest`, deterministic seed/link discovery order, and deterministic
scope/dedup decisions, WebAgent4J guarantees deterministic logical: seed ordering, frontier ordering,
normalized identities, dedup decisions, depth assignment, scope decisions, **page result ordering**
(structural, not merely provable - see [Concurrency model](#concurrency-model)), failure ordering,
link discovery ordering (document order), statistics, and termination reason (with explicit
precedence: `CANCELLED` > `FAIL_FAST` > `MAX_PAGES_REACHED` > `COMPLETED`).

**Not** guaranteed deterministic: wall-clock duration, browser scheduling/JavaScript execution
timing, or `Throwable` identity in a failure's `cause()` - the same exclusions the HTTP crawler's own
determinism contract documents, for the same reason.

## Resource ownership

The one crawler-owned page (created via `browser.newPage()` on first use, reused for every
navigation - see [Concurrency model](#concurrency-model)) is always closed when the crawl ends -
success, failure, `failFast`, or cancellation - verified by `BrowserCrawlerTest
.crawlerOwnedPagesAreAlwaysClosed` and, against a real browser, `BrowserCrawlerRobustnessIT
.bcRob013CrawlerOwnedPagesAreAlwaysClosed`. The caller-supplied `IBrowser` is never closed unless
`closeBrowserOnCompletion(true)` is explicitly set.

## Examples

```java
try (IBrowser browser = WebAgent.browser().playwright().chromium().headless(true).launch()) {
    BrowserCrawlRequest request =
            BrowserCrawlRequest.builder(browser)
                    .seed("https://example.com/")
                    .maxDepth(2)
                    .maxPages(50)
                    .build();
    BrowserCrawlResult result = new BrowserCrawler().crawl(request);
    result.pages().forEach(page -> System.out.println(page.finalUrl()));
}
```

See `webagent4j-examples` for runnable `BrowserCrawlSimpleExample` and
`BrowserCrawlSessionExample`.

## Current limitations

- Only a single navigation lane is supported - `maxConcurrency` must be `1` - see
  [Concurrency model](#concurrency-model).
- Only `FrameCrawlPolicy.TOP_LEVEL_ONLY` is implemented - see [Frames](#frames).
- `history.pushState()`-only SPA transitions are not tracked - see [SPA support](#spa-support).
- No intermediate redirect hop list - see [Redirect semantics](#redirect-semantics).
- Two different requested URLs redirecting to the same final URL are both navigated - see
  [URL identities](#url-identities-and-deduplication).
- No download detection: if navigation triggers a browser download instead of a rendered document,
  behavior is backend-defined - `IPage` exposes no content-type/download signal to detect this.
- The stability fingerprint detects DOM element-count and link-`href` changes, not every possible
  content mutation - see [Stability](#stability) for the exact, deliberately bounded contract.
- Observation is bounded (`maxElements(2000)`); a page that exceeds it becomes an explicit
  `OBSERVATION_TRUNCATED` failure rather than a silently incomplete success - see
  [Observation truncation](#observation-truncation). Its `maxElements(2000)` bound is not guaranteed
  identical to the stability fingerprint's 2000-link digest cap - see [Stability](#stability).
- After stability succeeds, `page.url()`, `page.observe(...)`, and `page.title()` are not bounded by
  any further deadline - only navigation and stability are natively bounded by the backend today. See
  [Navigation timeout](#navigation-timeout) for the exact, honest scope of what is and is not
  enforced.
- `BrowserCrawlerRobustnessIT` (BC-ROB-001..020, STABILITY-002, real Playwright, in
  `webagent4j-integration-tests`) covers the adversarial scenario matrix this phase's own
  instructions asked for; it does not duplicate the dedicated `webagent4j-robustness-tests`
  100-scenario corpus's element-level model, which was never designed for a crawl-graph concept.
- No CLI `crawl-browser` command was added - out of scope per this phase's own instructions unless it
  fits cleanly, and a real browser process/session argument does not fit the existing CLI's
  argument model without its own design pass.

## Compatibility

Three additive public API changes were introduced. No existing method signature changed and no
existing type was removed. Two are unconditionally source- and binary-compatible (new default
methods with a body - nothing that does not already call them is affected); the third is additive
but not unconditionally consequence-free for every possible caller:

- `IPage` (`webagent4j-browser-api`) gained a default method, `navigate(String, Duration)`, and a
  new type, `NavigationTimeoutException`, in the same package - required to make
  `navigationTimeout` genuinely authoritative rather than a best-effort suggestion (see
  [Navigation timeout](#navigation-timeout)). Every existing `IPage` implementation remains
  source-compatible unchanged: the new method has a default body, so nothing that does not already
  call it is affected.
- `IPage` also gained a second default method, `waitForCondition(String, Duration)`, and a second
  new type, `ConditionTimeoutException`, in the same package - required to make the stability wait
  genuinely bounded by the backend rather than by an interruptible-only-between-polls Java loop (see
  [Stability](#stability)). Same source-compatibility guarantee as above: default body, nothing
  affected unless it is called.
- `CrawlDecisionType` (`webagent4j-crawler-api`) gained one new enum constant, `REJECT_CANCELLED`
  (see [Cancellation](#cancellation) for why it belongs on the shared type rather than a
  browser-crawler-local one). No exhaustive `switch` over this enum exists anywhere in this
  repository today, confirmed by search - but a *downstream* consumer's own exhaustive `switch`
  expression over `CrawlDecisionType` (legal, idiomatic Java against an enum from another module)
  would fail to compile against this new constant until updated to handle it. That is a normal,
  expected consequence of adding an enum constant, not a signature change or a removed type, but it
  is not literally true to call it "no consequence for any existing caller" - so it is called out
  explicitly here rather than folded into a blanket "all backward-compatible" claim.

`IBrowserCrawler` is, and remains, a new, separate contract from `ICrawler` - both
`ICrawler#crawl(CrawlRequest)` and `ICrawlScopePolicy#evaluate(URI, URI, CrawlRequest)` are bound to
the concrete, HTTP-shaped `CrawlRequest` record, so `BrowserCrawler` cannot implement `ICrawler`
without forcing browser-specific configuration into fields that do not fit it - exactly what this
phase's own design constraints forbid. This mirrors `HttpCrawler`/`ICrawler`: two clear
implementations for two genuinely different backends, not one over-unified abstraction. The HTTP
crawler itself (`webagent4j-crawler`, `webagent4j-crawler-api`'s existing behavior) is otherwise
completely unchanged - its full test suite remains green.
