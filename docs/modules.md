# Module graph

Arrows below mean "depends on."

| Module | Direct WebAgent4J dependencies | Responsibility | Intended consumer | Stability classification |
|---|---|---|---|---|
| `webagent4j-bom` | none | Consumer version alignment | Maven consumers | Supported Maven metadata |
| `webagent4j-common` | none | Base exceptions, timeouts, and retry policy | Engines and advanced applications | Supported API/SPI |
| `webagent4j-wait` | common | Monotonic deadlines, polling, and stability windows | Engine implementors and advanced applications | Supported API/SPI |
| `webagent4j-locator-api` | none | Immutable locator definitions and fluent contracts | Applications and backend implementors | Supported API |
| `webagent4j-dom` | common, extraction-api, locator-api | Backend-neutral live element and scoped-query contracts | Applications and backend implementors | Supported API |
| `webagent4j-observation-api` | dom, locator-api | Semantic values, rendering/diff options, and capture SPI | Applications and backend implementors | Supported API/SPI |
| `webagent4j-observation` | browser-api, observation-api | Semantic transformation, policies, observers, diagnostics, and events | Applications and policy implementors | Supported API/SPI; `.internal` types unsupported |
| `webagent4j-locator` | common, dom, locator-api, wait | Planning, discovery ports, filtering, scoring, and ambiguity | Applications and locator strategy implementors | Supported API/SPI; `.internal` types unsupported |
| `webagent4j-verification` | dom, locator-api, observation-api, wait | Deterministic conditions, composition, and bounded polling | Applications | Supported API |
| `webagent4j-action` | common, dom, locator-api, observation-api, verification, wait | Action lifecycle, stabilization, results, and audit events | Applications and browser backend implementors | Supported API/SPI; `.internal` types unsupported |
| `webagent4j-browser-api` | action, dom, extraction, extraction-api, locator, locator-api, observation-api | Browser/page lifecycle contracts and provider SPI | Applications and browser backend implementors | Supported API/SPI |
| `webagent4j-browser-playwright` | action, browser-api, dom, locator, locator-api, observation | Playwright backend, observation adapter, and provider | Runtime composition | Supported artifact; implementation-public provider only |
| `webagent4j-core` | browser-api | Browser facade and provider discovery | Applications | Supported API |
| `webagent4j-http` | common | Empty non-browser transport boundary | Nobody | Reserved and unsupported; not in BOM |
| `webagent4j-storage` | common | Empty persistence boundary | Nobody | Reserved and unsupported; not in BOM |
| `webagent4j-extraction-api` | locator-api | Extraction requests, results, provenance, converters, validators, and failures | Applications and conversion/validation implementors | Supported API/SPI |
| `webagent4j-extraction` | common, dom, extraction-api, locator, locator-api, wait | Extraction engine reusing the locator engine | Applications | Supported API |
| `webagent4j-crawler-api` | common | Backend-neutral HTTP crawler contracts and policies | Applications and crawler policy implementors | Supported API/SPI |
| `webagent4j-crawler` | common, crawler-api, wait | Sequential HTTP crawler, HTTP fetcher, parsing, and frontier | Applications and fetch/parser implementors | Supported API/SPI; `.internal` types unsupported |
| `webagent4j-browser-crawler` | common, crawler-api, browser-api, wait | Single-lane crawler for JavaScript-rendered pages | Applications | Supported API; `.internal` types unsupported |
| `webagent4j-workflow` | action | Typed variables, conditions, sequential fail-fast orchestration, and results | Applications and workflow extension implementors | Supported API/SPI |
| `webagent4j-recording` | workflow | Schema-V1 JSON recording and pure offline comparison | Applications | Supported API |
| `webagent4j-plugin-api` | locator | Explicit trusted locator-provider discovery and immutable registration | Applications and plugin implementors | Supported API/SPI |
| `webagent4j-testing` | none | Empty shared test-fixture boundary | Nobody yet | Unsupported; zero public types; not in BOM |
| `webagent4j-cli` | core; Playwright at runtime | Command-line application | CLI users | Separate CLI compatibility surface |
| `webagent4j-examples` | core, crawler, crawler-api, workflow; Playwright at runtime | Executable examples | Contributors and learners | Documentation/sample code, not API |
| `webagent4j-integration-tests` | core, Playwright, plugin-api, testing, workflow | Architecture and browser integration tests | Contributors | Build/test infrastructure |
| `webagent4j-robustness-tests` | core, Playwright | Profile-gated adversarial corpus and cross-phase journeys | Contributors | Build/test infrastructure |

The 28 reactor modules have been classified above. The effective-public production inventory and
the exact consumer/SPI/runtime/implementation split are defined in
[API stability policy](api-stability.md#supported-surface-classifications).

The separate locator API module allows `IElement.find()` without a Maven dependency cycle. The DOM
module depends only on immutable, backend-neutral contracts; the locator engine depends on DOM element
inspection; and the Playwright adapter depends on both stable layers.

The separate observation API module provides the same dependency inversion for `IPage.observe()`.
Browser API exposes only immutable observation contracts and the snapshot SPI; the observation engine
orchestrates semantic policies; the backend implements bounded capture. No public observation type
exposes Playwright.

Reserved modules (`http`, `storage`) are intentionally empty and unsupported. They are retained as
reactor boundaries but no longer appear in the BOM; a future implementation would require its own
public API review. No Maven dependency cycle exists.

`webagent4j-crawler` graduated from a reserved module to a real implementation in Phase 0.6 (see
[http-crawler.md](http-crawler.md)); its dependency set changed entirely in the process (it no
longer depends on the still-reserved `http`/`storage` modules - the HTTP fetcher lives directly in
`webagent4j-crawler`, since nothing else currently needs a standalone transport module).
`webagent4j-workflow` graduated the same way in Phase 0.8, keeping its existing single dependency
on `webagent4j-action` (see [workflow.md](workflow.md)). `webagent4j-recording` graduated the same
way in Phase 0.9-A, keeping its existing single WebAgent4J dependency on `webagent4j-workflow` and
adding one external, non-public-API dependency: `jackson-databind`, used only inside
`JsonWorkflowRecordingCodec` - no Jackson type appears in any public method signature (see
[recording.md](recording.md)).

`webagent4j-plugin-api` graduated in Phase 0.9-B while preserving its existing one-way dependency on
`webagent4j-locator`. It owns explicit `ServiceLoader` discovery and immutable registration only;
locator, core, workflow, and recording never depend on it. See [plugins.md](plugins.md).

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
