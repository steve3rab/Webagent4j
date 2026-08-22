# API stability policy

This policy defines which WebAgent4J contracts are intended to become compatible at `1.0.0`. It
applies to published Java and Maven surfaces, not to every declaration that happens to use Java's
`public` modifier.

WebAgent4J is still pre-1.0. Until `1.0.0`, necessary breaking cleanups are allowed when they are
documented in the changelog and, when migration is not obvious, in the migration guide. The
current cleanup is documented in [migration-to-1.0.md](migration-to-1.0.md). Shared behavioral
rules and intentional domain differences are defined in [contracts.md](contracts.md).

## Starting with 1.0

Starting with `1.0.0`, supported Java APIs, SPIs, and Maven coordinates follow Semantic Versioning:

- a patch release fixes compatible behavior and does not intentionally break supported source or
  binary contracts;
- a minor release may add compatible API and SPI surface but does not intentionally remove or
  incompatibly change supported contracts;
- a major release may make incompatible source, binary, or documented behavioral changes.

Security fixes may require exceptional action. Any such exception will be narrowly scoped and
called out prominently in release notes.

The command-line interface is versioned separately from the Java API. Its documented command names,
options, exit codes, and machine-readable output become compatibility commitments only when the CLI
guide says so. Internal console wording is not a Java API promise.

## Supported surface classifications

The production inventory contains 368 effective public types, excluding examples, integration
tests, and robustness tests. They are classified as follows for the proposed 1.0 surface:

| Classification | Types | Compatibility intent |
| --- | ---: | --- |
| Supported consumer API | 296 | Application-facing contracts, values, facades, engines, and documented exceptions |
| Supported SPI | 32 | Deliberate implementation or callback points listed below |
| Public for a runtime mechanism | 1 | `PlaywrightBrowserProvider`, public for `ServiceLoader`, not an application entry point |
| Testing-support API | 0 | `webagent4j-testing` is empty and makes no fixture commitment |
| Implementation-public | 38 | Cross-package implementation types under `.internal.`; unsupported despite Java visibility |
| CLI application entry point | 1 | `WebAgentCommand`; governed by the separate CLI contract |

Effective public means that the type and all enclosing types are public. Compiler-generated record
members and inherited `Object` methods do not create a separate support classification.

### Supported consumer API

A supported consumer API is a public type or member documented for direct application use in
[public-api.md](public-api.md), its domain guide, or generated Javadoc. Its documented validation,
ordering, absence, failure, ownership, and side-effect semantics are part of the contract.

The `io.webagent4j.*.internal` package convention is an explicit non-API marker. Public types in
such packages exist only because Java package boundaries are used inside the implementation. They
may change without a major release and must not be imported by applications.

### Supported SPIs

The following 32 types are deliberate 1.0 SPI candidates. Their contracts include documented
callback ordering, null handling, failure propagation, and ownership; WebAgent4J does not promise
to sandbox third-party implementations.

- Time and polling: `IMonotonicClock`, `IWaitProbe`, `IWaitSleeper`.
- Locator composition: `IInteractabilityChecker`, `ILiveLocatorContext`, `ILocatorBackend`,
  `ILocatorEventListener`, `ILocatorStrategy`, `ILocatorStrategyRegistry`, `ITextNormalizer`.
- Observation capture and policies: `IObservationSource`, `PageSnapshot`, `SnapshotElement`,
  `SnapshotElementState`, `IElementCapabilityResolver`, `ILocatorDefinitionFactory`,
  `IObservationEventListener`, `IObservationFilter`, `IObservationRedactionPolicy`.
- Action and extraction: `IActionBackend`, `IStabilizationStrategy`, `IExtractionValidator`,
  `IValueConverter`.
- Browser discovery: `IBrowserProvider`.
- Crawling: `ICrawlDeduplicator`, `ICrawlScopePolicy`, `IUrlNormalizer`, `IHtmlLinkExtractor`,
  `IHttpFetcher`.
- Workflows: `IWorkflowActionFactory`, `IWorkflowCondition`.
- Plugins: `ILocatorStrategyProvider`.

Interfaces used only to consume an engine, such as `ILocatorEngine` and `ICrawler`, remain consumer
API rather than SPIs merely because an application could technically implement them.

### Runtime-public and implementation-public types

`PlaywrightBrowserProvider` must be public so Java `ServiceLoader` can instantiate it. Applications
select Playwright through `WebAgent`, not by constructing that provider. Its public visibility is a
runtime constraint, not a promise that its constructor or concrete type is application API.

The 38 effective public types in implementation packages are grouped under:

- `io.webagent4j.action.internal`;
- `io.webagent4j.browsercrawler.internal`;
- `io.webagent4j.crawler.internal`;
- `io.webagent4j.locator.internal`;
- `io.webagent4j.observation.internal`.

Moving these types during this phase would create broad package churn without reducing an actual
consumer contract. Their unsupported status is therefore made explicit instead.

## Maven artifact policy

The BOM aligns versions only for supported, consumable production artifacts. Empty placeholders
`webagent4j-http`, `webagent4j-storage`, and `webagent4j-testing` are reactor boundaries, not
supported dependencies, and are not managed by the BOM.

The proposed supported 1.0 artifacts are:

- `webagent4j-bom`, `webagent4j-common`, `webagent4j-wait`, `webagent4j-locator-api`,
  `webagent4j-dom`, `webagent4j-observation-api`, `webagent4j-locator`,
  `webagent4j-verification`, `webagent4j-action`, `webagent4j-browser-api`,
  `webagent4j-observation`, `webagent4j-browser-playwright`, `webagent4j-core`;
- `webagent4j-extraction-api`, `webagent4j-extraction`, `webagent4j-crawler-api`,
  `webagent4j-crawler`, `webagent4j-browser-crawler`;
- `webagent4j-workflow`, `webagent4j-recording`, and `webagent4j-plugin-api`.

`webagent4j-cli` is a distributable application. Examples, integration tests, robustness tests,
empty reserved artifacts, and the parent reactor POM are not supported application libraries.

## Compatibility dimensions

Source, binary, and behavior compatibility are reviewed independently. A signature can remain
binary compatible while a new required-argument invariant changes behavior; conversely, changing a
return type can break already compiled callers even when ordinary source code still compiles.

Supported behavior includes:

- validation and nullability documented by Javadoc;
- deterministic ordering and immutable collection snapshots;
- typed expected absence versus genuine backend failure;
- resource ownership and thread-safety statements;
- the absence of hidden retries around potentially side-effecting work.

The cross-module interpretation of these commitments is maintained in
[Cross-module contracts](contracts.md). A domain guide may deliberately define a narrower contract,
such as browser-crawler cancellation or HTTP-crawler partial success, without creating a universal
framework abstraction.

It does not include object identity, exception object identity, wall-clock durations, unordered
third-party input, browser/network timing, or undocumented diagnostic prose.

## Nullability, values, and collections

Required public arguments reject `null`. Optional values use `Optional` and never return a null
`Optional`. Immutable values and results defensively snapshot incoming collections, and returned
collections do not expose mutable internal state. IDs reject null, blank, or otherwise locally
invalid values at construction. A documented diagnostic payload may retain null when null is the
value being rejected, as with a directly invoked `IExtractionValidator`.

`IPage#evaluate(String)` intentionally returns `Object` because JavaScript values are dynamically
typed. `WaitSample` intentionally carries an opaque `Optional<Object>` stability key. These are
documented dynamic/opaque boundaries, not general-purpose metadata maps. No supported signature
uses `Map<String, Object>`.

## Failures, diagnostics, and Java serialization

Expected absence is represented by a typed result, typed exception, or documented empty
`Optional`. An unexpected backend/runtime failure is not converted into absence, `null`, an empty
collection, or fabricated success.

Structured failures retain typed fields for programmatic handling. Their `message` and `toString()`
representations are safe summaries and may deliberately omit URIs, raw extracted values, provider
messages, and raw causes. `ActionFailure`, `CrawlFailure`, and `BrowserCrawlFailure` retain a raw
`Throwable` for advanced in-process diagnostics; callers must treat it as sensitive and must not
persist or expose it without their own redaction policy.

Native Java serialization is not a supported persistence or compatibility format for WebAgent4J
public types. Inheriting `Serializable` from `Throwable` does not imply support. Structured
exceptions whose state could be silently lost explicitly reject `ObjectOutputStream` and
`ObjectInputStream` with `NotSerializableException`. Use stable structured fields for in-process
handling and the documented recording JSON format for recordings.

## Recording schema compatibility

Recording JSON compatibility is separate from Java/Maven SemVer. The JSON document carries an
explicit integer `schemaVersion`; the only current version is V1. Decoding is strict and never
falls back from an unknown version. Enums are encoded by name, never by Java ordinal, and the format
does not rely on native Java serialization.

This stabilization phase does not change schema V1. A future schema change must document whether it
is backward readable, introduce the appropriate schema version, and provide migration guidance.

## Resource ownership and thread safety

| Kind | Thread-safety contract | Resource ownership |
| --- | --- | --- |
| Immutable definitions, options, IDs, results, snapshots, and registries | Reusable across threads once safely published | No external resource ownership |
| Builders and prepared operation objects | Caller-confined unless their Javadoc explicitly says otherwise | No ownership transfer unless documented |
| Engines and crawlers | No blanket thread-safety promise; collaborators and one invocation must remain caller-confined unless documented otherwise | They do not close caller-supplied collaborators unless the domain contract explicitly assigns ownership |
| `IBrowser` and `IPage` | Explicitly not thread-safe | The creating caller owns and closes them; closing a browser closes its pages and backend resources |
| `PluginLoader#load(ClassLoader)` | Independent loader calls; provider callbacks run synchronously | The caller retains ownership of the supplied class loader; it is neither closed nor globally cached |
| `IHttpFetcher` responses and Java streams | Follow the declaring method's try-with-resources/close contract | Ownership transfers only where Javadoc says so |

When a more specific type's Javadoc states a stronger guarantee, that statement governs that type.

## Determinism and side effects

Determinism is logical: for the same inputs and the same sequence of environment responses,
ordering, classification, comparison, and redaction decisions are reproducible. It is not a claim
that external browser/network state or elapsed durations are identical.

WebAgent4J does not add hidden retries around potentially side-effecting actions, workflow steps,
plugin callbacks, or browser evaluation. A retry exists only where the relevant public policy
explicitly opts in and defines its safety boundary.
