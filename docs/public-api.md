# WebAgent4J public API

This page maps supported artifacts to their application-facing entry points. It is intentionally shorter than the generated Javadoc and domain guides; it does not repeat every behavioral matrix.

## Runtime baseline

WebAgent4J targets Java 21 bytecode and supports Java 21 or later. Public Java/Maven compatibility follows the policy in [api-stability.md](api-stability.md). Browser support qualification is listed in [support-matrix.md](support-matrix.md).

## Choosing modules

Depend on the narrowest set that covers your use case.

| Need | Artifact(s) | Main entry points |
| --- | --- | --- |
| Browser lifecycle | `webagent4j-core` plus a runtime provider such as `webagent4j-browser-playwright` | `WebAgent`, `IBrowser`, `IPage` |
| Semantic locator definitions | `webagent4j-locator-api` | `LocatorDefinition`, fluent locator contracts |
| Locator engine | `webagent4j-locator` | `ILocatorEngine`, `LocatorEngine`, strategies/configuration |
| DOM abstraction | `webagent4j-dom` | `IElement`, element-scoped operations |
| Deterministic waits | `webagent4j-wait` | `WaitEngine`, `WaitBudget`, `WaitPolicy` |
| Semantic observation | `webagent4j-observation-api`, `webagent4j-observation` | `Observation`, `ObservationOptions`, observation SPI/engine |
| Verified browser actions | `webagent4j-action` | action builder/prepared action, `ActionResult`, `IActionPlan` |
| Governed execution | `webagent4j-common`, `webagent4j-action` | `IExecutionPolicy`, `ExecutionPolicies`, `IActionPolicy`, `ActionPolicies`, `INetworkPolicy`, `NetworkPolicies` — see [Governed execution](governed-execution.md) |
| Verification | `webagent4j-verification` | `IVerification`, `Verifications`, `VerificationResult` |
| Extraction | `webagent4j-extraction-api`, `webagent4j-extraction` | `ExtractionRequest`, `ExtractionResult`, `ExtractionEngine` |
| HTTP crawling | `webagent4j-crawler-api`, `webagent4j-crawler` | `ICrawler`, `HttpCrawler`, `CrawlRequest`, `CrawlResult` |
| Browser crawling | `webagent4j-browser-crawler` | `IBrowserCrawler`, `BrowserCrawler`, browser crawl request/result |
| Workflow orchestration | `webagent4j-workflow` | `Workflow`, `WorkflowEngine`, variables, conditions, step results |
| Recording | `webagent4j-recording` | `WorkflowRecorder`, `JsonWorkflowRecordingCodec`, `WorkflowReplayVerifier` |
| Trusted custom locator plugins | `webagent4j-plugin-api` | `PluginLoader`, `PluginRegistry`, `ILocatorStrategyProvider` |
| Version alignment | `webagent4j-bom` | Maven BOM |
| Command-line application | `webagent4j-cli` | `version`, `observe`/`inspect`, `screenshot` — see [CLI](cli.md) |

`webagent4j-common` contains supported low-level exceptions, retry policy, and timing abstractions for engines and advanced integrations. It is not currently BOM-managed; direct consumers must use the same version as the rest of WebAgent4J unless that BOM policy is changed before their release.

## Supported versus visible Java types

Java `public` is not by itself a compatibility promise. Types in `io.webagent4j.*.internal` packages are implementation-public and unsupported for application imports. `PlaywrightBrowserProvider` is public so `ServiceLoader` can construct it; applications normally select the backend through `WebAgent` rather than instantiating the provider.

The definitive classifications are in [API stability](api-stability.md). Generated Javadoc may include implementation-public types; package classification still governs support.

## Browser stack

A typical browser application composes:

```text
Application
   |
   v
webagent4j-core
   |
   v
browser-api / dom / locator / observation / action / verification / extraction
   ^
   |
Playwright provider at runtime
```

Public contracts do not expose native Playwright `Page`, `Locator`, `Frame`, or browser objects.

## HTTP crawler stack

The HTTP crawler is an independent vertical using JDK HTTP plus crawler contracts. It does not require Playwright. The browser crawler is also a separate contract: it intentionally does not implement `ICrawler`, because browser navigation/stability/failure semantics do not map honestly onto HTTP response semantics.

## Extension points

Supported SPIs are deliberate, synchronous extension boundaries, not general-purpose plugin hooks. The plugin facility adds exactly one discovery mechanism for `ILocatorStrategyProvider`; ordinary SPIs can still be provided directly by application composition. Arbitrary extension callbacks are trusted in-process code and are not retried or sandboxed unless a specific SPI says otherwise.

## Reserved artifacts

`webagent4j-http`, `webagent4j-storage`, and `webagent4j-testing` are reserved/empty reactor boundaries. They are not supported application dependencies and are not managed by the BOM.

`webagent4j-examples`, `webagent4j-integration-tests`, and `webagent4j-robustness-tests` are repository support modules, not application libraries.

## Contract entry points

- Cross-domain result, timeout, resource, threading, security, and side-effect rules: [contracts.md](contracts.md)
- Thread safety and ownership by domain: relevant guide plus [api-stability.md](api-stability.md)
- Network/plugin/metadata trust: [security-model.md](security-model.md)
- Unsupported or incomplete capabilities: [limitations.md](limitations.md)
