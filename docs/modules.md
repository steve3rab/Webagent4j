# Module graph

Arrows below mean "depends on."

| Module | Direct WebAgent4J dependencies | Current responsibility |
|---|---|---|
| `webagent4j-bom` | none | Consumer version alignment |
| `webagent4j-common` | none | Exceptions, timeouts, retry policy |
| `webagent4j-wait` | common | Deterministic wait/stability primitive: monotonic deadlines, polling, stability windows |
| `webagent4j-locator-api` | none | Immutable locator definitions and generic fluent contracts |
| `webagent4j-dom` | common, extraction-api, locator-api | Backend-neutral live element and scoped query contract |
| `webagent4j-observation-api` | dom, locator-api | Immutable semantic model, options, renderer, fingerprint, diff, capture SPI |
| `webagent4j-observation` | browser-api, observation-api | Semantic transformation, policies, observers, diagnostics, events |
| `webagent4j-locator` | common, dom, locator-api, wait | Planning, discovery ports, filtering, scoring, ambiguity, diagnostics |
| `webagent4j-verification` | dom, locator-api, observation-api, wait | Deterministic conditions, composition, and bounded polling |
| `webagent4j-action` | common, dom, locator-api, observation-api, verification, wait | Commands, lifecycle orchestration, safe retries, structured results, and audit events |
| `webagent4j-browser-api` | action, dom, extraction, extraction-api, locator, locator-api, observation-api | Browser/page lifecycle contracts |
| `webagent4j-browser-playwright` | action, browser-api, dom, locator, locator-api, observation | Playwright action backend, browser lifecycle, batch observation adapter, and service provider |
| `webagent4j-core` | browser-api | Public facade and provider discovery |
| `webagent4j-http` | common | Reserved non-browser transport boundary |
| `webagent4j-storage` | common | Reserved persistence boundary |
| `webagent4j-extraction-api` | locator-api | Backend-neutral extraction request/result/provenance, converters, validators, and failure taxonomy |
| `webagent4j-extraction` | common, dom, extraction-api, locator, locator-api, wait | Deterministic extraction engine reusing the existing locator engine - no second DOM resolution engine |
| `webagent4j-crawler-api` | common | Backend-neutral HTTP crawler contracts: `CrawlRequest`/`CrawlResult`/`CrawledPage`, failure taxonomy, scope/dedup ports - no HTTP client, no HTML parser, no Playwright |
| `webagent4j-crawler` | common, crawler-api, wait | Deterministic, sequential HTTP crawler engine: `java.net.http.HttpClient` fetcher, jsoup link extraction, BFS frontier, URL normalization/deduplication/scope policy, redirect and retry handling. No browser. See [http-crawler.md](http-crawler.md) |
| `webagent4j-workflow` | action | Reserved workflow boundary |
| `webagent4j-recording` | workflow | Reserved record/replay boundary |
| `webagent4j-plugin-api` | locator | Reserved plugin boundary |
| `webagent4j-testing` | none | Reserved shared test-fixture boundary - currently has no source code |
| `webagent4j-cli` | core; Playwright at runtime | Public-API CLI |
| `webagent4j-examples` | core; Playwright at runtime | Executable public-API example |
| `webagent4j-integration-tests` | core, Playwright, testing | Architecture and browser integration tests |
| `webagent4j-robustness-tests` | core, Playwright | Profile-gated deterministic adversarial corpus and cross-phase journeys |

The separate locator API module allows `IElement.find()` without a Maven dependency cycle. The DOM
module depends only on immutable, backend-neutral contracts; the locator engine depends on DOM element
inspection; and the Playwright adapter depends on both stable layers.

The separate observation API module provides the same dependency inversion for `IPage.observe()`.
Browser API exposes only immutable observation contracts and the snapshot SPI; the observation engine
orchestrates semantic policies; the backend implements bounded capture. No public observation type
exposes Playwright.

Reserved modules (`http`, `storage`, `workflow`, `recording`, `plugin-api`) are intentionally empty
until a tested vertical needs their public API. This prevents placeholder types from becoming
accidental compatibility commitments. No Maven dependency cycle exists.

`webagent4j-crawler` graduated from a reserved module to a real implementation in Phase 0.6 (see
[http-crawler.md](http-crawler.md)); its dependency set changed entirely in the process (it no
longer depends on the still-reserved `http`/`storage` modules - the HTTP fetcher lives directly in
`webagent4j-crawler`, since nothing else currently needs a standalone transport module).

`webagent4j-extraction-api` depends only on `locator-api`, never on `dom`: an `ExtractionRequest`
describes where to search (a `LocatorDefinition`) and how to read/convert/validate what is found,
never a live `IElement` reference. `webagent4j-dom` depends on `extraction-api` in the other
direction, for exactly one method - `IElement#extract(ExtractionRequest<T>)`, which reads an
already-resolved element's own `text()`/`attributes()`/`value()` directly (no locator search) and
applies the same `ExtractionRequest#convertAndValidate(String)` pipeline step `webagent4j-extraction`
uses. This one-directional edge (`dom -> extraction-api`, never the reverse) is an ArchUnit-enforced
rule (`extractionApiRemainsIndependentFromDom`), the same pattern `dom -> locator-api` already uses
for `IElement.find()`. `webagent4j-extraction` is the deterministic engine
(`ExtractionEngine`), reusing `ILocatorEngine`/`ILiveLocatorContext` rather than a parallel
resolution engine.

The action module owns orchestration but no browser-native implementation. Verification owns
read-only conditions and polling. Browser adapters implement `IActionBackend`; target resolution,
preconditions, stabilization, observations, and result construction remain backend-neutral.
