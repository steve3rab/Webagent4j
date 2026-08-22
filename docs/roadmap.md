# Roadmap

No dates are implied. Each milestone requires implementation, tests, relevant integration coverage,
Javadoc, user documentation, and a green `clean verify` build.

- **0.1 Browser foundation:** first Playwright vertical, observations, semantic link resolution,
  verified clicks, CLI, and open-source build foundation.
- **0.2 Semantic locators:** more roles, labels, deterministic diagnostics, frames, and shadow DOM.
- **0.3 Observation engine:** richer landmarks, forms, tables, compact rendering, and JSON contracts.
- **0.4 Actions and verification:** typing, selection, waits, preconditions, and more postconditions.
- **0.5 Extraction:** deterministic text/attribute/value/list/table extraction reusing the existing
  locator engine, typed conversion, validation, provenance, and structured failures. See
  [docs/extraction.md](extraction.md).
- **0.6 HTTP crawler:** deterministic, sequential, backend-neutral HTTP crawler - frontier,
  normalization, deduplication, host/domain scope policy, redirect and retry handling, and
  structured failures. No browser, no `robots.txt` enforcement yet, no rate limiting, no
  persistent storage. See [docs/http-crawler.md](http-crawler.md).
- **0.7 Browser crawler:** dynamic pages, sessions, and cancellation - a deterministic, single-lane
  browser crawler reusing `webagent4j-browser-api`/`webagent4j-wait`, running entirely within the
  one caller-supplied `IBrowser` session a crawl is given (the crawler neither creates nor isolates
  that session - see [Session model](browser-crawler.md#session-model)), top-level frame discovery.
  Navigation runs on one thread only (`IBrowser`/`IPage` carry no thread-safety contract to build
  physical concurrency on), so determinism is structural rather than a scheduling guarantee. See
  [docs/browser-crawler.md](browser-crawler.md).
- **0.8 Workflows:** a deterministic, sequential orchestration layer over `webagent4j-action` -
  immutable, reusable workflow definitions; typed, write-once variables with explicit
  required/optional inputs; masked secret variables with a centralized, tested redaction contract;
  a small, fixed set of fail-closed declarative conditions; real action-pipeline integration through
  single-use preparation factories (no `IActionPlan` ever cached in a definition); fail-fast-only
  execution with structured per-step and overall results; no hidden retries, no workflow-wide
  timeout, no cancellation. See [docs/workflow.md](workflow.md).
- **0.9-A Recording foundation:** a deterministic, versioned recording that excludes raw workflow
  values, preserves engine-redacted diagnostics, and has an explicit verbatim-metadata trust
  boundary (`WorkflowRecorder`); canonical JSON encoding/decoding (`IWorkflowRecordingCodec`); and
  a pure, offline structured comparison between a recording and a new execution's `WorkflowResult`
  (`WorkflowReplayVerifier`). A recording is data, not a program: it has no `execute()` method and
  cannot replay itself - there is deliberately no automatic live replay of browser actions in this
  phase. See [docs/recording.md](recording.md).
- **0.9-B Plugins:** explicit, deterministic `ServiceLoader` discovery for trusted custom locator
  strategies, with immutable registries, fail-closed validation, structured safe failures, and no
  default auto-discovery. See [docs/plugins.md](plugins.md). Persistence for a recording (database or
  filesystem) and automatic live replay execution are not part of this phase either - they remain
  future candidates, to be scoped as their own separate decision if and when they are taken up,
  never a default or an implicit promise of any phase number.
- **1.0-A Public API stabilization (completed):** inventory and classify every production module
  and effective public type; remove or document accidental surface; correct pre-1.0 source, binary,
  nullability, diagnostics, serialization, backend-failure, and Maven boundary issues; define the
  [compatibility policy](api-stability.md) and [migration notes](migration-to-1.0.md). This phase adds
  no product capability and does not release `1.0.0`.
- **1.0-B Cross-module contract consistency (completed):** aligned result/failure invariants,
  timeout and resource semantics, fail-closed backend classification, threading claims, and safe
  rendering across the stabilized candidate surface. The maintained matrix is
  [docs/contracts.md](contracts.md). This phase adds no product capability, schema change, or new
  extension point.
- **1.0-C Hardening and adversarial testing (next):** validate the consistent contracts under
  broader failure and boundary conditions. Not started.
- **1.0-D Release readiness:** final release engineering and publication readiness. Not started.

Post-1.0 candidates include MCP and agent adapters, distributed crawling, more browser backends,
advanced observation, and opt-in self-healing. These remain optional consumers of the non-AI core.
