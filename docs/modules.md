# Module graph

Arrows below mean "depends on."

| Module | Direct WebAgent4J dependencies | Current responsibility |
|---|---|---|
| `webagent4j-bom` | none | Consumer version alignment |
| `webagent4j-common` | none | Exceptions, timeouts, retry policy |
| `webagent4j-locator-api` | none | Immutable locator definitions and generic fluent contracts |
| `webagent4j-dom` | common, locator-api | Backend-neutral live element and scoped query contract |
| `webagent4j-observation-api` | dom, locator-api | Immutable semantic model, options, renderer, fingerprint, diff, capture SPI |
| `webagent4j-observation` | browser-api, observation-api | Semantic transformation, policies, observers, diagnostics, events |
| `webagent4j-locator` | common, dom, locator-api | Planning, discovery ports, filtering, scoring, ambiguity, diagnostics |
| `webagent4j-verification` | none | Deterministic condition model |
| `webagent4j-action` | dom, verification | Explicit actions, structured results, audit events |
| `webagent4j-browser-api` | action, dom, locator, locator-api, observation-api | Browser/page lifecycle contracts |
| `webagent4j-browser-playwright` | browser-api, dom, locator, locator-api, observation | Playwright backend, batch observation adapter, and service provider |
| `webagent4j-core` | browser-api | Public facade and provider discovery |
| `webagent4j-http` | common | Reserved non-browser transport boundary |
| `webagent4j-storage` | common | Reserved persistence boundary |
| `webagent4j-extraction` | dom | Reserved extraction boundary |
| `webagent4j-crawler` | http, storage | Reserved crawling composition boundary |
| `webagent4j-workflow` | action | Reserved workflow boundary |
| `webagent4j-recording` | workflow | Reserved record/replay boundary |
| `webagent4j-plugin-api` | locator | Reserved plugin boundary |
| `webagent4j-testing` | none | Shared test-fixture boundary |
| `webagent4j-cli` | core; Playwright at runtime | Public-API CLI |
| `webagent4j-examples` | core; Playwright at runtime | Executable public-API example |
| `webagent4j-integration-tests` | core, Playwright, testing | Architecture and browser integration tests |

The separate locator API module allows `IElement.find()` without a Maven dependency cycle. The DOM
module depends only on immutable, backend-neutral contracts; the locator engine depends on DOM element
inspection; and the Playwright adapter depends on both stable layers.

The separate observation API module provides the same dependency inversion for `IPage.observe()`.
Browser API exposes only immutable observation contracts and the snapshot SPI; the observation engine
orchestrates semantic policies; the backend implements bounded capture. No public observation type
exposes Playwright.

Reserved modules are intentionally empty until a tested vertical needs their public API. This prevents
placeholder types from becoming accidental compatibility commitments. No Maven dependency cycle
exists.
