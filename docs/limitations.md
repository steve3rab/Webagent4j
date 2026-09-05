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
  identity revalidation and the native backend call to the exact same physical handle) is wired for
  every target-bound Playwright action method: `click`, `doubleClick`, `type`/`fill` (including the
  secret variant), `typeSequentially` (including the secret variant, added in 1.2.0's Governed
  Actions V2), `clear`, `select`, `check`, `uncheck`, `focus`, `blur`, `hover`, `scrollTo`, `submit`,
  `pressKey`, `upload`, and `download`. None of them fall back to a second, independently
  re-resolved `Locator` for the actual native call; see
  [Governed execution](governed-execution.md#target-identity-binding) and
  `PlaywrightVerifiedTargetActionMatrixTest`/`ActionPolicyTargetIdentityIT` for the exact evidence.
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
- Deterministic `if`/`else` branching (`WorkflowSteps.ifElse`/`ifThen`) is supported - a condition evaluated exactly once selecting exactly one of two step sequences, nested conditional branching is supported up to the framework's 64-level conditional nesting limit (each branch measured independently, never summed) - see [workflow.md#branching](workflow.md#branching). A definition exceeding that limit is rejected at build time with a controlled error, never a `StackOverflowError`. It is a narrow control-flow primitive, not a general rules/expression DSL.
- No loops, recursion over data, DAG scheduler, parallel branches, fork/join, `switch`/`case`, workflow variables reassignable across branches, transactions/sagas, persistence, checkpoint/resume, scheduling, cron, external event triggers, or YAML/JSON workflow language.
- No workflow-wide timeout/cancellation abstraction. Actions keep their own timeout/interruption semantics; a conditional step's own two structural boundaries observe the executing thread's interrupt status, the same primitive the action pipeline already relies on for its own boundary checks - see [workflow.md#branching](workflow.md#branching).
- No hidden workflow retry.
- Secret masking is framework-rendering protection, not encryption or storage security.
- `WorkflowEngine#executeWithTree` returns a structured `WorkflowExecutionTree` alongside the existing flat `WorkflowResult` - see [workflow.md#execution-tree](workflow.md#execution-tree). It is an observational, runtime-only hierarchical view of what actually executed, built once during the same execution pass; it has no timestamps, no duration/profiling data, no distributed-tracing span or exporter integration (no OpenTelemetry, Micrometer, Zipkin, or Jaeger). A conditional's non-selected branch never contributes an execution node - there is no branch speculation.
- `WorkflowPlanner.plan(workflow)` returns a `WorkflowExecutionPlan` - see [workflow.md#execution-plan](workflow.md#execution-plan). It is a static, structural description built entirely from the definition; it never executes a step, never evaluates a condition or guard, never resolves or verifies a backend target, and never invokes an `IWorkflowActionFactory`. It is not a dry run, not a replay, not a simulation, and never predicts whether a runtime-dependent action, condition, or policy decision will succeed. It represents every structurally possible branch of a conditional, not the one a runtime decision would select - the opposite of the execution tree's non-selected-branch-zero-nodes guarantee, and the two types are never merged or interchangeable.
- `Workflow.Builder#validate()` returns a structured `WorkflowValidationReport` - see [workflow.md#validation-report](workflow.md#validation-report). It explains the exact same structural invariants `build()` enforces, derived from the same internal analysis; there is no independent second validation algorithm. It never evaluates a condition or guard, never invokes an `IWorkflowActionFactory`, and never touches a backend, browser, or network resource. `build()` remains fully fail-closed regardless of whether `validate()` is ever called: the report never makes an invalid definition executable, and it is not an auto-fix, a style linter, a constant-condition optimizer, or an execution simulator. Diagnostics are bounded (`diagnosticsTruncated()`) rather than accumulated without limit.

## Recording

- A recording is data, not an executable program, in both schema versions.
- No automatic *real side-effect* live replay (browser/action recreation against a real backend), retry inference, storage backend, screenshot/DOM/HAR/video capture, or alternate serialization format, in either schema version.
- Two JSON schema versions are supported (V1 and V2), each with its own disjoint version-number space; an unknown version, or a payload's version number belonging to the other schema's space, fails explicitly rather than falling back. There is no implicit or automatic V1-to-V2 migration - see [recording.md#recording-v2](recording.md#recording-v2).
- Caller/action metadata identifiers are persisted verbatim and are not secret channels, in both schema versions.
- Recording V2 ([recording.md#recording-v2](recording.md#recording-v2)) captures the executed workflow's `WorkflowExecutionPlan` and a tree mirroring `WorkflowExecutionTree`, with a typed `WorkflowPlanOutput` (name, type, secret classification) per published output instead of V1's bare output-variable name. There is currently no published JSON Schema file for V2 (unlike V1's); the Java model and codec are the sole authoritative description.
- Deterministic Replay ([recording.md#deterministic-replay](recording.md#deterministic-replay)) validates a Recording V2 trace against a live `Workflow` and reconstructs its recorded decision path - it never evaluates a condition, never invokes an action factory, never resolves or verifies a backend target, and never performs any side effect. Eligibility rests on two guarantees, not plan equality alone: the recording's own internal plan/tree coherence, guaranteed unconditionally at construction (see [recording.md#recording-v2](recording.md#recording-v2)), and the recorded plan matching the live workflow's current plan, which is what `ReplayValidator` itself checks. Only a `COMPLETED` recording can be replayed in this scope; replaying a `FAILED` trace is not yet defined. Real governed-target side-effect replay (actually re-invoking an action, under this codebase's existing exact-target-revalidation and interruption/deadline guarantees) is not implemented - this is a deliberate, documented 1.3 scope decision, not an oversight.

## Plugins

- Locator strategies are the only discovered plugin extension point.
- No plugin sandbox, process isolation, lifecycle callbacks, dependency injection, config schema, plugin directory, annotation scanning, network download, hot reload/unload, file watching, dependency solving, or version negotiation.
- Providers/strategies are trusted Java code and can block, perform I/O, mutate global state, or fail.
- Runtime strategy failures are not silently converted to empty results.

## Security/compliance exclusions

WebAgent4J does not bypass CAPTCHA, authentication, anti-bot controls, consent, access controls, or site policy. It does not automatically make a crawl legally or contractually authorized. It does not rotate proxies or disguise browser fingerprints.

See [Security model](security-model.md) for the complete trust boundary.
