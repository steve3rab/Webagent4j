# Known limitations

WebAgent4J is a deterministic semantic automation foundation, not a universal visual agent or a network-security product. This page lists current product-level limitations without tying them to historical development phases.

## Semantic/UI boundaries

- Canvas-only interfaces, remote-desktop streams, image-only controls without alternatives, pseudo-element-only labels, and meaning conveyed only by color/position may be unresolvable.
- There is no OCR, computer vision, pixel-coordinate targeting, or AI fallback.
- Duplicate controls with indistinguishable semantic context intentionally remain ambiguous. Add an accessible distinction or a hard scope rather than relying on DOM order.
- Fuzzy matching is conservative text similarity, not language understanding.
- A page may still mutate between final resolution and native input. The backend is the final authority for the last interaction race.
- Open shadow-root behavior follows supported Playwright selector capabilities; closed shadow roots are not inspectable. Explicit XPath has Playwright's usual shadow-DOM limitations.

## Browser and frame boundaries

- Frame criteria are intentionally limited to supported `id`, `name`, `title`, and URL matching modes; there is no arbitrary frame CSS/XPath/fuzzy DSL.
- Browser-engine implementation is broader than full robustness qualification in general, but for Chromium, Firefox, and WebKit specifically, exact-head evidence has observed all three passing the complete adversarial corpus together, and the release workflow gates every engine equally. That evidence is currently Linux-only: it does not establish any engine's qualification on Windows or macOS, since browser and operating-system qualification are independent axes. See [support-matrix.md](support-matrix.md#browser-and-robustness-qualification-by-operating-system).
- Live browser objects are not generally thread-safe.

## Observation

- Observation is bounded and detached, not an atomic browser transaction. Mutations during capture can produce warnings or truncation/failure according to the domain.
- Observing a resolved page/frame does not mean the engine recursively traverses every nested frame automatically.
- Accessible-name extraction covers the supported deterministic native/ARIA surface; it is not a promise to reproduce every browser accessibility-tree detail.
- Table/list observation is a bounded semantic summary, not the extraction API.

## Extraction

- Extraction itself does not orchestrate crawling, pagination, distributed scraping, infinite scroll, or multi-page workflows. Compose it explicitly with crawler/workflow/application code.
- There is no AI schema inference, OCR, generalized automatic JSON-LD discovery, or visual-table reconstruction from arbitrary layout markup.
- Generic container element types use normal Java runtime type checks; deep generic element-type validation is not provided.

## HTTP crawler

- Sequential BFS only; no high-concurrency/distributed mode.
- No JavaScript execution, SPA navigation, browser session state, clicks, form execution, or infinite-scroll handling.
- No automatic browser fallback.
- `robots.txt` is not enforced.
- No Public Suffix List is used for registrable-domain computation; host/subdomain policy is literal and caller-configured.
- No general SSRF protection beyond configured scheme/host/domain restrictions. The caller owns destination authorization.
- Canonical links are observed metadata, not automatically trusted as crawl identity.

## Browser crawler

- Single navigation lane only; `maxConcurrency` must remain one under the current browser thread-safety contract.
- The caller supplies the browser/session. Session isolation across crawls is therefore the caller's responsibility unless separate browsers are used.
- No generic click-driven SPA exploration and no `history.pushState()`-only crawl discovery.
- No intermediate HTTP redirect-hop list because browser navigation follows redirects opaquely.
- No recursive frame enumeration/traversal policy beyond the supported top-level behavior.
- No `robots.txt` engine or universal SSRF protection.
- Navigation/stability share a bounded timeout, but post-stability `url()`, observation, and title capture are separate calls without a common backend-native deadline.
- A browser-initiated download is not a general crawl-document type.

## Governed execution

- `IActionPolicy`/`INetworkPolicy` are opt-in; nothing is governed unless a caller explicitly
  configures one. Direct `IPage`/`IBrowser` calls made outside a governed action or crawl bypass
  both entirely.
- Atomic action-target identity verification (`IElement#verifiedForExecution()`, which binds
  identity revalidation and the native backend call to the exact same physical handle) is currently
  wired for the Playwright adapter's `click()` only. Every other Playwright action method (`fill`,
  `check`, `uncheck`, `select`, `hover`, `focus`, `blur`, `press`, `upload`, `submit`, `scrollTo`,
  `download`, `doubleClick`) still revalidates identity via the boolean-only
  `isStillTheOriginallyResolvedTarget()` and then performs its native call through a second,
  independently re-resolved `Locator` - the residual TOCTOU window this method exists to close
  remains open for those methods.
- `INetworkPolicy` is not a general SSRF firewall. `HttpCrawler` binds its actual transport
  connection to the exact addresses a policy verified - closing the DNS-rebinding gap between
  check and connect - only when the configured policy implements `INetworkAddressAuthority` (the
  built-in `NetworkPolicies` policy does; a fully custom `INetworkPolicy` lambda does not unless it
  implements that capability too).
- A governed `NAVIGATE` action or `BrowserCrawler` visit can only detect, never prevent, a
  browser-internal redirect landing somewhere a network policy would have denied - unlike
  `HttpCrawler`, which controls its own redirect loop and can prevent every hop. Browser navigation
  also has no transport-level pinning at all: its own DNS resolution for policy evaluation is never
  bound to whatever address the browser's network stack ultimately connects to.
- `PinnedSocketHttpTransport` (the pinned connection `HttpCrawler` uses) is GET-only, HTTP/1.1, and
  never pools or reuses a connection across requests - matching what `HttpCrawler` itself needs, not
  a general-purpose HTTP client.
- A configured policy is ordinary, trusted, unsandboxed Java code - the same trust posture as a
  plugin. WebAgent4J cannot prevent or undo a side effect a malicious or buggy policy performs
  itself during evaluation.
- `networkPolicy(...)` only applies to a `NAVIGATE` action; no other action type has a network
  destination knowable before its backend call.
- No policy persistence, serialization, remote/LLM-assisted authorization, or governance DSL is
  provided.

## Workflows

- Sequential and fail-fast only.
- No loops, recursion, DAG scheduler, parallel branches, fork/join, general `if/else` step DSL, transactions/sagas, persistence, checkpoint/resume, scheduling, cron, external event triggers, or YAML/JSON workflow language.
- No workflow-wide timeout/cancellation abstraction. Actions keep their own timeout/interruption semantics.
- No hidden workflow retry.
- Secret masking is framework-rendering protection, not encryption or storage security.

## Recording

- A recording is data, not an executable program.
- No automatic live replay, browser/action recreation, retry inference, storage backend, screenshot/DOM/HAR/video capture, or alternate serialization format.
- Only JSON schema V1 is supported. Unknown versions fail explicitly.
- Caller/action metadata identifiers are persisted verbatim and are not secret channels.

## Plugins

- Locator strategies are the only discovered plugin extension point.
- No plugin sandbox, process isolation, lifecycle callbacks, dependency injection, config schema, plugin directory, annotation scanning, network download, hot reload/unload, file watching, dependency solving, or version negotiation.
- Providers/strategies are trusted Java code and can block, perform I/O, mutate global state, or fail.
- Runtime strategy failures are not silently converted to empty results.

## Security/compliance exclusions

WebAgent4J does not bypass CAPTCHA, authentication, anti-bot controls, consent, access controls, or site policy. It does not automatically make a crawl legally or contractually authorized. It does not rotate proxies or disguise browser fingerprints.

See [Security model](security-model.md) for the complete trust boundary.
