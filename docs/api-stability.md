# API stability policy

This policy defines the compatibility surface intended for WebAgent4J 1.0 and later. Until `1.0.0` is actually published, the source tree is a release candidate and necessary pre-release corrections remain possible. Once `1.0.0` is published, the commitments below govern supported surfaces.

## Semantic Versioning

For supported Java APIs, SPIs, and Maven coordinates:

- patch releases fix compatible behavior and do not intentionally break supported source or binary contracts;
- minor releases may add compatible API/SPI surface but do not intentionally remove or incompatibly change supported contracts;
- major releases may make incompatible source, binary, or documented behavioral changes.

Security or correctness fixes may require exceptional behavior changes. Such exceptions must be narrowly scoped, fail-safe, and prominently documented in release notes and migration guidance.

Compatibility is reviewed across source, binary, and documented behavior. Keeping a method signature unchanged does not make an incompatible validation or failure-semantics change automatically safe.

## Supported surface classifications

### Consumer API

A public type/member is supported consumer API when it is documented for application use in this documentation or generated Javadoc and is not in an `internal` package. Its documented validation, ordering, absence, failure, ownership, thread-safety, timeout, and side-effect behavior forms part of the contract.

### SPI

Supported SPIs are deliberate implementation/callback points. The current SPI families include:

- time and polling (`IMonotonicClock`, `IWaitProbe`, `IWaitSleeper`);
- locator composition/backends/listeners/strategies/normalization;
- observation capture, policies, factories, listeners, filters, and redaction;
- action backend and stabilization;
- extraction converters and validators;
- browser provider discovery;
- HTTP crawler fetch/extraction/scope/dedup/normalization ports;
- workflow action factories and conditions;
- locator plugin provider discovery.

An interface is not automatically an SPI merely because Java permits applications to implement it. Consumer-facing engine interfaces remain consumer API unless their guide documents an implementation contract.

### Runtime-public types

A concrete type may be public only because Java runtime discovery requires it. `PlaywrightBrowserProvider` is the primary example: it is instantiated by `ServiceLoader` but is not the normal application entry point.

### Implementation-public types

Types under `io.webagent4j.*.internal` are unsupported even when Java visibility is public. They may move or change in a non-major release when implementation needs require it.

## Artifact policy

The supported 1.0 library artifacts are the BOM and the production browser, locator, wait, observation, action, verification, extraction, crawler, workflow, recording, and plugin modules listed in [modules.md](modules.md).

`webagent4j-common` is treated as supported low-level API/SPI for advanced use. The current BOM does not manage it; a direct dependency therefore needs an explicit version equal to the rest of the WebAgent4J release. This should be resolved at the build-policy level before claiming that every supported artifact is BOM-managed.

Reserved `webagent4j-http`, `webagent4j-storage`, and `webagent4j-testing` modules are not supported application artifacts. Examples and test modules are not compatibility surfaces.

## Nullability, values, collections

- Required public arguments reject `null` unless explicitly documented otherwise.
- Optional values use `Optional`; APIs do not return a null `Optional`.
- Immutable values/results defensively snapshot incoming collections and do not expose mutable internal collections.
- IDs reject null, blank, or otherwise invalid local state according to their own syntax rules.
- Ratios, scores, weights, confidence values, margins, and similar bounded floating-point values must be finite as well as inside their documented range. `NaN` is never a valid bypass.
- Public elapsed durations are non-negative. Configured timeouts/poll intervals are positive except the documented zero-budget immediate-probe behavior of `WaitBudget`.

## Failures and diagnostics

Expected absence is represented by a typed result, typed exception, or documented empty `Optional` according to the domain. Unexpected backend/runtime failure is never silently converted into absence, null, an empty collection, or fabricated success.

Only renderings explicitly documented as safe/structural may be logged as such. General record `toString()` output is not automatically a logging boundary. Some structured failures retain a raw `Throwable` for advanced in-process diagnosis; callers must treat it as sensitive.

Native Java serialization is not a supported persistence or compatibility format. Use stable structured fields and, for workflow recordings, the documented JSON schema V1.

## Recording compatibility

Recording JSON compatibility is versioned separately from Java binary compatibility. Schema V1 is strict: unknown schema versions do not fall back, duplicate/unknown fields and impossible state combinations are rejected, and enums are encoded by name. A future incompatible shape requires a new schema version and migration policy; it must not silently reinterpret V1.

## Resource ownership and thread safety

- Immutable definitions, IDs, results, snapshots, and registries are shareable after safe publication unless their Javadoc says otherwise.
- Builders, prepared actions, browsers, pages, frames, and live elements are caller-confined unless explicitly documented otherwise.
- `IBrowser` and its pages are caller-owned. Closing the browser closes its pages and backend resources.
- Engines have no blanket concurrency promise merely because they retain little state; injected collaborators and live browser objects must also satisfy the required concurrency contract.
- Callers retain ownership of a class loader passed to `PluginLoader`.
- Ownership transfers only when a domain guide or method Javadoc says so.

## Determinism and side effects

Determinism is logical, not a promise of identical wall-clock timing or external browser/network state. For the same inputs and environment responses, WebAgent4J aims for reproducible ordering, classification, redaction, and comparison decisions.

Potentially side-effecting backend actions are never hidden inside polling loops and are not blindly retried. A retry exists only where a documented policy opts in and defines the safe retry boundary. See [Cross-module contracts](contracts.md).

## CLI policy

The CLI is a separate compatibility surface even though it is built from the same repository version. Before declaring the CLI stable, its supported command names, options, exit codes, and machine-readable output must be documented and release-tested. Internal console prose is not a Java API promise.

If no separate CLI compatibility document exists for a release, consumers must not infer that every human-readable message is SemVer-stable.
