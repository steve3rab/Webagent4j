# Known limitations

WebAgent4J is a deterministic semantic automation foundation, not a universal visual agent. Its safe
behavior depends on machine-readable browser semantics and current backend capabilities.

## Document boundaries

- Page, element, and frame scopes are all supported through the public API: `IPage#frame()` /
  `IFrame#frame()` return an `IFrameLocator` with `single()`/`tryFind()` terminal operations - no
  `first()` or `all()`, since a frame has no scoring dimension to rank candidates by and DOM order
  is never a hidden tie breaker - the same 0/1/N classification and bounded-wait semantics element
  locators already have. Every criterion (`id`, `name`, `title`, `url`) filters candidates before
  that 0/1/N classification is applied, so a `url` criterion can disambiguate two frames that share
  a `name`, rather than one being forced to an ambiguous failure before `url` ever gets a chance to
  narrow the match. Frame resolution is backend-neutral - no native Playwright `Frame`,
  `FrameLocator`, or `Page` type is exposed - and cross-origin iframes work the same as same-origin
  ones, without weakening browser security. See [locators.md](locators.md#frames) for the full
  contract.
- Frame criteria are limited to `id`, `name`, `title`, and URL (exact/case-insensitive/contains/
  starts-with/ends-with/regex); there is no CSS/XPath frame selector and no fuzzy matching anywhere
  in a frame query, mirroring the same deliberate absence of a "huge selector DSL" the element
  locator API already avoids. Only exact and case-insensitive-exact criteria are supported for
  `id`/`name`/`title` themselves; a `FUZZY` URL criterion is rejected explicitly - never silently
  treated as `CONTAINS` or any other mode.
- A genuine backend or runtime failure encountered while inspecting a frame candidate's URL (a
  disconnected browser, a closed context, or any other opaque failure) always propagates unchanged.
  It is never absorbed into a typed "not found" outcome or an empty `tryFind()` result; only a
  candidate's own `<iframe>` element vanishing between discovery and inspection - a normal
  detachment race - is treated as "does not currently match" this poll.
- Playwright semantic selectors can traverse supported open shadow roots. Closed shadow roots are not
  inspectable, and explicit XPath has Playwright's usual shadow-DOM limitations.
- Native and correctly authored ARIA controls work best. Custom controls require a valid role,
  accessible name, state, and keyboard or pointer behavior.
- Invalid or missing ARIA degrades to the semantics the browser can expose. It never authorizes a
  guess based only on styling.

## Interfaces without reliable semantics

Canvas applications, remote-desktop streams, image maps without alternatives, pseudo-element-only
labels, inaccessible icon controls, and interfaces whose meaning exists only in color or position may
be `UNRESOLVABLE`. WebAgent4J has no OCR, computer-vision, or visual-coordinate fallback.

Exact role and accessible-name queries cannot distinguish duplicate controls with identical semantic
context. Use an element scope or add an accessible distinction; otherwise `single()` intentionally
returns `AMBIGUOUS`. Conservative fuzzy matching is not a language model and may reject short,
misspelled, multilingual, or closely related action text.

## Dynamic interaction

Semantic references re-resolve replacement nodes, and bounded waits handle delayed insertion. A page
can still mutate between final resolution and browser input. Interactability checks use attachment,
visibility, enabled state, viewport geometry, pointer events, and center-point coverage, but the browser
remains the final authority during animations, overlays, navigation, or removal races.

The deterministic benchmark currently gates Chromium. Standard CI and nightly jobs cover supported
operating systems, while broader Firefox and WebKit robustness matrices can be introduced after their
results and runtime are stable enough to be non-flaky.

## Explicit exclusions

WebAgent4J does not bypass CAPTCHA, authentication, anti-bot systems, access controls, or consent. It
does not rotate proxies or disguise browser fingerprints. Public-site tests, transactions, purchases,
bookings, comments, messages, or destructive forms are not part of the deterministic regression suite.

There is no AI, OCR, or visual recognition implementation in this phase. Future optional fallbacks
must preserve explicit uncertainty and action safety. Phase 0.8 (see
[docs/workflow.md](workflow.md)) adds a deterministic, sequential *orchestration* layer over
`webagent4j-action` - typed variables, masked secrets, and fail-closed conditions - not a general
programming language, an expression/DSL engine, or any form of AI.

The deterministic extraction engine (see [docs/extraction.md](extraction.md)) does not yet implement
crawling, pagination, distributed/scraping-at-scale scenarios, AI-based schema inference, OCR,
visual/computer-vision extraction, generalized automatic JSON-LD/structured-data discovery,
infinite-scroll orchestration, advanced network-level retries, or reconstructing a "visual table"
laid out with non-table markup. These belong to later phases.

## HTTP crawler (Phase 0.6)

The HTTP crawler (see [docs/http-crawler.md](http-crawler.md)) is implemented, but explicitly does
not implement: JavaScript execution, SPA navigation, browser rendering, dynamic DOM, clicks, forms,
or infinite-scroll handling; browser cookies or session state (it is stateless between requests);
visual/computer-vision extraction; automatic browser fallback; distributed or high-concurrency
crawling (this phase is intentionally sequential); advanced sitemap orchestration; a workflow
engine; AI-based ranking or extraction; or MCP/agent tooling.

`robots.txt` is not enforced in this phase. `ICrawlScopePolicy` is the extension point a future
phase would use to add it; this document does not claim compliance it does not implement. No
Public Suffix List is used, so "domain" scoping compares literal hosts rather than computing a
true registrable domain - the caller is responsible for choosing correct `allowedHosts`/seeds.
There is no SSRF protection beyond the scheme/host/domain restrictions the caller configures - the
caller remains responsible for the destinations it authorizes.

## Browser crawler (Phase 0.7)

The browser crawler (see [docs/browser-crawler.md](browser-crawler.md)) is implemented, but
explicitly does not implement: frame discovery beyond the top-level document (`FrameCrawlPolicy`
values other than `TOP_LEVEL_ONLY` are rejected at construction - no public API exists yet to
enumerate every frame on a page); generic click-driven SPA exploration; tracking
`history.pushState()`-only URL changes as separate crawl entries; an intermediate HTTP redirect hop
list; download detection (a browser-initiated download is not distinguished from a rendered
document); a `robots.txt` engine; a workflow engine; AI-based ranking or extraction; or MCP/agent
tooling. `robots.txt` and SSRF limitations are the same as the HTTP crawler's, above. Only a single
navigation lane is supported - `maxConcurrency` must be `1`, since neither `IBrowser` nor `IPage`
carries a thread-safety contract to build physical navigation concurrency on (see
[browser-crawler.md#concurrency-model](browser-crawler.md#concurrency-model)). The integration suite
includes a dedicated real-Playwright adversarial suite, `BrowserCrawlerRobustnessIT`
(BC-ROB-001..014), covering cyclic graphs, duplicate fan-out, normalization dedup, exact `maxPages`/
`maxDepth` bounds, cancellation/failFast resource cleanup, backend failures, stability timeouts,
dynamic-DOM discovery boundaries, out-of-scope links/redirects, and deterministic repeated runs.

## Workflows (Phase 0.8)

The workflow module (see [docs/workflow.md](workflow.md)) is implemented, but is deliberately not a
general programming language. It does not implement: loops, `while`, `forEach`, recursion,
arbitrary graph execution or DAG scheduling, parallel branches, fork/join, an `if`/`else` branching
step (guarded sequential steps cover the supported cases), workflow-level or automatic action
retries beyond the action layer's own, compensation/sagas, transactions, persistence, resumable
workflows, checkpoints, distributed execution, scheduling, cron, timers, external event triggers, a
YAML/JSON workflow DSL, a visual editor, dynamic plugin discovery or `ServiceLoader` step
registration, recording/replay, or any AI/MCP/agent integration. There is also no workflow-wide hard
timeout and no workflow cancellation in this phase: a Java-side deadline wrapped around an
otherwise-unbounded step can be false safety, and general cancellation is deferred until a
backend-neutral abstraction is deliberately designed rather than duplicated ad hoc from the browser
crawler's crawl-specific `CancellationToken`. Secret masking is a rendering guarantee for
framework-owned representations, not encryption or a vault - see
[workflow.md#secret-masking](workflow.md#secret-masking) for the exact contract and its limits.

## Recording (Phase 0.9-A)

The recording module (see [docs/recording.md](recording.md)) is implemented, but is deliberately
not a browser automation replay engine. It does **not** implement: automatic live replay of
recorded browser actions (there is no `WorkflowRecording.execute()` and no code path that
deserializes a recording and re-drives a browser); action recreation from a recording; automatic
retries derived from a recording; persistence to a database or filesystem; a plugin SPI or
`ServiceLoader` discovery mechanism for recordings; screenshot, DOM, observation, HAR, or video
capture; any AI/MCP/agent integration; or any serialized format other than the one canonical JSON
schema (`RecordingSchemaVersion.V1`) - there is no YAML, XML, or protobuf encoding. Replay
verification (`WorkflowReplayVerifier`) is a pure, synchronous, offline structured comparison
between a recording and a caller-supplied `WorkflowResult` from the caller's own new execution -
never a re-execution the module performs itself. Recreating browser actions from a recording, if it
is ever added, is explicitly deferred to a later phase (see
[recording.md#future-live-replay-boundary](recording.md#future-live-replay-boundary)) as an
opt-in capability, not a default.
