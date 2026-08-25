# Module graph

Arrows below mean “depends on”. The default Maven reactor contains 27 modules. `webagent4j-robustness-tests` is an additional profile-gated module activated with `-Probustness`; describing all 28 as the unconditional default reactor is therefore inaccurate.

| Module | Responsibility | Intended consumer | Stability |
| --- | --- | --- | --- |
| `webagent4j-bom` | Consumer version alignment | Maven consumers | Supported metadata |
| `webagent4j-common` | Base failures, retry, timing abstractions | Engines/advanced applications | Supported API/SPI; not currently BOM-managed |
| `webagent4j-wait` | Monotonic deadlines, polling, stability | Engine implementors/advanced applications | Supported API/SPI |
| `webagent4j-locator-api` | Locator definitions/fluent contracts | Applications/backends | Supported API |
| `webagent4j-dom` | Backend-neutral live element contract | Applications/backends | Supported API |
| `webagent4j-observation-api` | Detached semantic model and capture SPI | Applications/backends | Supported API/SPI |
| `webagent4j-observation` | Semantic transformation/policies | Applications/policy implementors | Supported API/SPI; `.internal` unsupported |
| `webagent4j-locator` | Resolution, filtering, scoring, ambiguity | Applications/strategy implementors | Supported API/SPI; `.internal` unsupported |
| `webagent4j-verification` | Read-only deterministic conditions | Applications | Supported API |
| `webagent4j-action` | Action lifecycle/results/audit | Applications/backends | Supported API/SPI; `.internal` unsupported |
| `webagent4j-browser-api` | Browser/page/frame contracts/provider SPI | Applications/backends | Supported API/SPI |
| `webagent4j-browser-playwright` | Playwright implementation/provider | Runtime composition | Supported artifact; provider runtime-public |
| `webagent4j-core` | Browser facade/provider discovery | Applications | Supported API |
| `webagent4j-extraction-api` | Extraction requests/results/conversion/validation | Applications/extensions | Supported API/SPI |
| `webagent4j-extraction` | Deterministic extraction engine | Applications | Supported API |
| `webagent4j-crawler-api` | HTTP-crawler-neutral values/policies | Applications/extensions | Supported API/SPI |
| `webagent4j-crawler` | Sequential HTTP crawler | Applications/extensions | Supported API/SPI; `.internal` unsupported |
| `webagent4j-browser-crawler` | Single-lane rendered-page crawler | Applications | Supported API; `.internal` unsupported |
| `webagent4j-workflow` | Sequential typed action orchestration | Applications/extensions | Supported API/SPI |
| `webagent4j-recording` | Schema-V1 recording/offline comparison | Applications | Supported API |
| `webagent4j-plugin-api` | Explicit trusted locator-provider discovery | Applications/plugin authors | Supported API/SPI |
| `webagent4j-http` | Empty reserved transport boundary | Nobody | Unsupported; not in BOM |
| `webagent4j-storage` | Empty reserved persistence boundary | Nobody | Unsupported; not in BOM |
| `webagent4j-testing` | Empty reserved test boundary | Nobody | Unsupported; not in BOM |
| `webagent4j-cli` | Command-line application | CLI users | Separate compatibility surface |
| `webagent4j-examples` | Executable examples | Learners/contributors | Documentation/sample code |
| `webagent4j-integration-tests` | Cross-module/browser integration verification | Contributors | Build infrastructure |
| `webagent4j-robustness-tests` | Adversarial corpus (`-Probustness`) | Contributors/release validation | Profile-gated build infrastructure |

## Architectural direction

- `locator-api` and `observation-api` break dependency cycles by placing backend-neutral contracts below implementation engines.
- `dom` can reference locator/extraction request types without making their engines depend back on DOM implementation details.
- `browser-api` exposes no native Playwright type.
- `browser-playwright` is an adapter depending inward on browser/domain contracts.
- HTTP crawler and browser crawler are separate verticals; neither forces the other's result model.
- `plugin-api` depends on locator but locator does not depend on plugin discovery.
- Recording depends on workflow and does not depend on browser, crawler, Playwright, or plugin discovery.

See [Architecture](architecture.md) for dependency rules and [API stability](api-stability.md) for compatibility classification.
