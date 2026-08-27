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
- Browser-engine implementation is broader than full robustness qualification. Chromium is the current release-gated engine; `develop` now contains nightly and release qualification infrastructure for Firefox and WebKit too, but neither is promoted to release-gated status pending observed exact-head evidence, and scheduled nightly activation additionally depends on the workflow reaching the repository's default branch. See [support-matrix.md](support-matrix.md).
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
