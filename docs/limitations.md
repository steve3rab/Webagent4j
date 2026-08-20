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

There is no AI, workflow engine, OCR, or visual recognition implementation in this phase. Future
optional fallbacks must preserve explicit uncertainty and action safety.

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
