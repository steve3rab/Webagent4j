# WebAgent4J

[![CI](https://github.com/steve3rab/Webagent4j/actions/workflows/ci.yml/badge.svg)](https://github.com/steve3rab/Webagent4j/actions/workflows/ci.yml)
[![Java 21+](https://img.shields.io/badge/Java-21%2B-ED8B00.svg?logo=openjdk&logoColor=white)](https://adoptium.net/)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)

WebAgent4J is a deterministic, backend-neutral web automation foundation for Java 21 or later. It
combines semantic element location, explicit browser actions, bounded observation, deterministic
extraction, crawling, workflows, recording, and verification behind contracts designed to fail
closed rather than guess.

Playwright is the first browser backend.

> [!IMPORTANT]
> `1.2.x` is the current stable line (latest: `1.2.0`); `1.1.x` (latest: `1.1.1`) is the previous
> stable line. Public Maven artifacts are not yet published from this repository's release workflow.
> Until publication is enabled, build and install the artifacts locally.

## Design goals

WebAgent4J is built around a small set of framework-wide guarantees:

- **Deterministic decisions** — for the same inputs and the same sequence of external responses,
  ordering, classification, normalization, redaction, and comparison decisions are reproducible.
- **Fail-closed target selection** — ambiguity, missing evidence, or opaque backend failure never
  authorizes a guessed browser target.
- **No hidden side-effect retry** — read-only resolution and verification may poll, but a browser
  side effect is not silently repeated by wait logic.
- **Backend-neutral public contracts** — supported application APIs do not expose native Playwright
  objects.
- **Explicit trust boundaries** — safe diagnostic renderings, caller metadata, raw failures,
  plugins, recordings, and network targets have documented security boundaries.
- **Bounded operations where promised** — locator, observation, action, crawler, and wait contracts
  define their timeout or capacity limits explicitly.
- **Small runtime surface** — no AI/LLM SDK, Spring, Jakarta EE, reactive runtime, or dependency
  injection framework is required.

## Requirements

- Java 21 or later
- Git
- Internet access for the first build when Maven dependencies and Playwright browser components are
  not already cached

Maven is supplied through the Maven Wrapper. The project compiles with `--release 21`.

## Build from source

```bash
git clone https://github.com/steve3rab/Webagent4j.git
cd Webagent4j
./mvnw clean verify
```

On Windows:

```powershell
.\mvnw.cmd clean verify
```

The standard reactor verifies formatting, static architecture rules, unit tests, integration tests,
coverage gates, and aggregate Javadoc. The profile-gated adversarial corpus can be run separately:

```bash
./mvnw -Probustness verify
```

See [`docs/testing.md`](docs/testing.md) and [`docs/hardening.md`](docs/hardening.md) for the quality
model.

## Use the libraries before public publication

Until public Maven publication is enabled, install the current source build into your local
repository:

```bash
./mvnw install
```

Then use one version property for the BOM:

```xml
<properties>
  <webagent4j.version>YOUR_WEBAGENT4J_VERSION</webagent4j.version>
</properties>

<dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>io.webagent4j</groupId>
      <artifactId>webagent4j-bom</artifactId>
      <version>${webagent4j.version}</version>
      <type>pom</type>
      <scope>import</scope>
    </dependency>
  </dependencies>
</dependencyManagement>

<dependencies>
  <dependency>
    <groupId>io.webagent4j</groupId>
    <artifactId>webagent4j-core</artifactId>
  </dependency>

  <dependency>
    <groupId>io.webagent4j</groupId>
    <artifactId>webagent4j-browser-playwright</artifactId>
    <scope>runtime</scope>
  </dependency>
</dependencies>
```

The authoritative supported-artifact list is maintained in
[`docs/api-stability.md`](docs/api-stability.md).

## Quick start

```java
import static io.webagent4j.verification.Verifications.urlContains;

import io.webagent4j.action.ActionResult;
import io.webagent4j.browser.IBrowser;
import io.webagent4j.browser.IPage;
import io.webagent4j.core.WebAgent;

try (IBrowser browser =
        WebAgent.browser()
                .playwright()
                .chromium()
                .headless(true)
                .launch()) {

    IPage page = browser.open("https://example.com");

    ActionResult<Void> result =
            page.action()
                    .click(
                            page.find()
                                    .link()
                                    .named("More information...")
                                    .reference())
                    .expect(urlContains("iana"))
                    .execute();

    result.throwIfFailed();
}
```

Use try-with-resources for browsers. The creating caller owns the browser unless a more specific API
explicitly transfers ownership. This one governed click is the smallest unit of what WebAgent4J
does; the next section shows the whole architecture working together.

## Browser workflow example

WebAgent4J is not just a Playwright wrapper with nicer locators - `webagent4j-workflow` and
`webagent4j-recording` add typed data flow, deterministic control flow, pre-execution validation
and planning, post-execution introspection, and a versioned, replayable recording format on top of
the governed action pipeline above. The walkthrough below exercises essentially all of it in one
coherent scenario: browsing a small paginated catalog, applying a discount only for premium
customers, and stopping once the last page is reached - never guessing, never looping forever.

The full runnable source is
[`webagent4j-examples/.../BoundedBrowserWorkflowShowcaseExample.java`](webagent4j-examples/src/main/java/io/webagent4j/examples/BoundedBrowserWorkflowShowcaseExample.java);
it compiles and is exercised as part of this repository's own build. It runs against a tiny local
HTTP fixture started inline in `main()` (omitted below for brevity, along with the small helper
methods) - never a public website - so the whole example stays deterministic and Internet-free.

```java
// Typed inputs: a live page, a boolean flag, a secret API key never printed or logged
// in the clear - and a conditional branch that runs a governed click only when true.
Workflow.Builder builder =
        Workflow.builder("catalog-browse")
                .requiredInput(PAGE)
                .requiredInput(API_KEY)
                .requiredInput(PREMIUM_CUSTOMER)
                .step(
                        WorkflowSteps.ifThen(
                                "apply-discount-if-premium",
                                WorkflowConditions.isTrue(PREMIUM_CUSTOMER),
                                List.of(clickStep("Apply Discount"))))
                .step(
                        WorkflowSteps.loop(
                                "paginate",
                                pageIndicatorNotAtLastPage(),
                                5, // maxIterations - no hidden infinite loop, ever
                                List.of(clickStep("Next"), readPageIndicatorStep())));

// Validate before ever touching the browser - structural only, zero side effects.
var report = builder.validate();
System.out.println("Valid: " + report.valid() + ", steps=" + report.stepCount());

Workflow workflow = builder.build();

// Inspect the side-effect-free execution plan: the loop appears once, as LOOP{BODY},
// never unrolled into 5 copies.
WorkflowExecutionPlan plan = WorkflowPlanner.plan(workflow);
System.out.println("Plan top-level steps: " + plan.nodes().size());

WorkflowInputs inputs =
        WorkflowInputs.builder()
                .put(PAGE, page)
                .put(API_KEY, "sk_demo_51fddc2c_not_real")
                .put(PREMIUM_CUSTOMER, true)
                .build();

// Execute the bounded pagination loop through the real action pipeline.
WorkflowExecution execution = new WorkflowEngine().executeWithTree(workflow, inputs);
execution.result().throwIfFailed();

// Inspect the actual execution tree: exactly the iterations that ran.
var loopNode = execution.tree().nodes().get(1);
System.out.println("Loop iterations recorded: " + loopNode.children().size());

// Capture Recording V2 and encode it to canonical JSON.
WorkflowRecorderV2 recorder = new WorkflowRecorderV2();
WorkflowRecordingV2 recording =
        recorder.record(
                new RecordingId("catalog-run-1"), Instant.now(), plan, execution);
String encoded = new JsonWorkflowRecordingV2Codec().encode(recording);
System.out.println("Recording encoded: " + encoded.length() + " bytes");

// Deterministic Replay: structural/decision replay only - it reproduces the recorded
// decisions and iteration count, but never re-clicks or re-visits the browser.
ReplayValidator.validate(recording, workflow)
        .ifPresentOrElse(
                failure -> System.out.println("Replay incompatible: " + failure.type()),
                () -> {
                    IReplayOutcome outcome =
                            WorkflowReplayer.replay(recording, workflow);
                    System.out.println("Replay outcome: " + outcome);
                });
```

**What this example guarantees:**

- The loop is bounded - `maxIterations = 5` is visible in the source, and reaching it while the
  continuation condition is still true would fail the workflow closed, never loop forever.
- The continuation condition is evaluated exactly once per iteration attempt, per `WorkflowEngine`'s
  own semantics - never re-evaluated while an iteration's body runs.
- Only the selected branch of `apply-discount-if-premium` ever runs - the discount click never
  happens for a non-premium customer, and there is no fallback or speculative execution.
- Every workflow variable is typed (`WorkflowVariable<T>`); `API_KEY` is a secret variable and is
  never exposed by any diagnostic rendering, the execution tree, or the recording.
- Every click is a governed, exact-target action - the click pipeline atomically reproves its
  physical target immediately before the backend call, exactly as it does outside a loop.
- `builder.validate()` inspects the definition before any browser is touched - zero side effects.
- `WorkflowPlanner.plan(workflow)` is a real, side-effect-free structural plan - the loop is
  represented once, never unrolled into five copies.
- The execution tree (`execution.tree()`) reflects exactly what ran: the actual iteration count,
  never a placeholder for an iteration that never started.
- Recording V2 captures only the actually-executed path, in exact order.
- Deterministic Replay reproduces the recorded decisions and iteration count without ever
  re-evaluating a condition or touching the browser - it is structural/decision replay only, never
  "browser replay."
- There is no hidden retry anywhere in this example: a failed click fails the workflow, once.

See [Workflows](docs/workflow.md) and [Recording](docs/recording.md) for the complete model behind
every step above.

### Bounded parallelism example

`WorkflowSteps.parallel` declares a fixed, structurally-represented set of read-only branches that
all run once the step is reached, joined deterministically:

```java
.step(
        WorkflowSteps.parallel(
                "check-pages",
                List.of(
                        List.of(readPageIndicatorStep()), // branch 0
                        List.of(readCatalogTotalStep())))) // branch 1
```

- The branch count is checked against `Workflow.MIN_PARALLEL_BRANCHES`/`MAX_PARALLEL_BRANCHES` (2-8)
  at build time - never unbounded, never a single implicit branch.
- Every Workflow `ACTION` step is unconditionally forbidden inside a branch - there is no
  caller-declarable exception; the framework has no way to mechanically verify that an arbitrary
  action factory's prepared action never performs an observable side effect, so this is rejected
  at validation time, fail closed, not fail open.
- Branches join in declaration order, not real completion order: if branch 0 fails, branch 1's
  result - even if it finished first - is discarded and reported `NOT_RUN`, preserving the same
  "exactly one failure, ordered before/after" guarantee that every other step type already provides.
- Each branch observes an isolated fork of workflow state; no branch can see another branch's
  in-flight variables, secrets, or outputs while any branch is still running.

See [Workflows](docs/workflow.md#bounded-parallelism) and
[Recording](docs/recording.md#bounded-parallelism) for the complete model, and
[Limitations](docs/limitations.md) for the caller-responsibility caveat around concurrent access to
a shared resource such as an `IPage`.

## Main capabilities

| Area | Main modules | Purpose |
| --- | --- | --- |
| Browser lifecycle | `webagent4j-core`, `webagent4j-browser-api` | Backend-neutral browser and page lifecycle |
| Playwright backend | `webagent4j-browser-playwright` | Browser execution adapter |
| Semantic location | `webagent4j-locator-api`, `webagent4j-locator`, `webagent4j-dom` | Deterministic live element resolution and scopes |
| Wait and stability | `webagent4j-wait` | Monotonic budgets, polling, stability windows |
| Observation | `webagent4j-observation-api`, `webagent4j-observation` | Bounded detached semantic page snapshots |
| Actions | `webagent4j-action` | Planning, dry-run, execution, stabilization, structured results |
| Governed execution | `webagent4j-common`, `webagent4j-action` | Opt-in action/network authorization with exact verified-target execution and decision provenance |
| Verification | `webagent4j-verification` | Read-only deterministic conditions and postconditions |
| Extraction | `webagent4j-extraction-api`, `webagent4j-extraction` | Typed text/attribute/value/list/table extraction |
| HTTP crawler | `webagent4j-crawler-api`, `webagent4j-crawler` | Deterministic sequential HTTP crawling |
| Browser crawler | `webagent4j-browser-crawler` | Single-lane crawling of JavaScript-rendered pages |
| Workflows | `webagent4j-workflow` | Typed deterministic workflows with conditional branching, bounded loops, bounded parallelism (`1.3`, in progress), validation, static planning, and structured execution results |
| Recording | `webagent4j-recording` | Schema-V1 recording and offline comparison; Recording V2 and Deterministic Replay (`io.webagent4j.recording.replay`) for `1.3`, in progress |
| Plugins | `webagent4j-plugin-api` | Explicit trusted custom locator strategies |
| CLI | `webagent4j-cli` | Small command-line application |

Reserved or test-only reactor modules are not automatically supported consumer artifacts. See
[`docs/modules.md`](docs/modules.md).

## Workflows

`webagent4j-workflow` orchestrates typed, deterministic sequences of actions and variable
assignments:

- **Typed data flow** — `WorkflowVariable<T>` inputs/outputs, write-once assignment, public/secret
  classification, and guard-aware, path-sensitive definite assignment: a step output is available
  downstream only when every reachable control-flow path guarantees it was published.
- **Deterministic branching** — `WorkflowSteps.ifElse`/`ifThen` evaluate a condition exactly once
  and run exactly one branch; the branch not selected produces zero step executions, zero action
  calls, and zero backend invocations. There is no speculative or fallback branch execution.
- **Bounded loops** (`1.3`, in progress) — `WorkflowSteps.loop` adds an explicitly-bounded repetition
  step: a mandatory `maxIterations` checked against a framework-wide maximum, a continuation
  condition evaluated exactly once per iteration attempt, and fail-closed behavior if the bound is
  reached while the condition is still true — never a disguised repeat-until-success mechanism.
  Arbitrary mutable inter-iteration state is explicitly out of scope. See
  [Workflows](docs/workflow.md#bounded-loops).
- **Bounded parallelism** (`1.3`, in progress) — `WorkflowSteps.parallel` declares a fixed set of 2-8
  branches (`Workflow.MAX_PARALLEL_BRANCHES`) that all structurally run once the step is reached,
  joined in deterministic branch-definition order regardless of real completion order; a bounded,
  per-step thread pool executes them with isolated per-branch state, deterministic output merge and
  failure-selection rules, and reactive cancellation of branches that can no longer affect the
  result. This first version is restricted to read-only/observational branches only — a Workflow
  `ACTION` step is unconditionally forbidden inside a branch, with no caller-declarable exception,
  since the framework cannot mechanically verify that an arbitrary action factory's prepared
  action never performs an observable side effect. Concurrent browser side effects (clicks,
  typing, navigation) are explicitly out of scope for this version. See
  [Workflows](docs/workflow.md#bounded-parallelism).
- **Three deliberately separate introspection views**, never merged or toggled between:

  ```text
  Validation Report  -> is this workflow definition valid, and why?
  Execution Plan     -> what can it structurally execute?
  Execution Tree     -> what did one execution actually do?
  ```

  `Workflow.Builder#validate()` never throws or mutates the builder. `WorkflowPlanner.plan(...)`
  never evaluates a condition/guard or calls an action factory. `WorkflowEngine#executeWithTree(...)`
  runs the workflow exactly once and returns the same result as `execute(...)`, plus a hierarchical
  view of the path actually taken.

`webagent4j-recording`'s Recording V2 format (`1.3`, in progress) captures the Execution Plan
together with a tree mirroring the Execution Tree above, and `io.webagent4j.recording.replay`
validates a recording's compatibility with a live workflow and deterministically replays its
recorded decision path — structural/decision replay only, never a re-invocation of an action factory
or a backend. See [`docs/workflow.md`](docs/workflow.md) and
[`docs/recording.md`](docs/recording.md#recording-v2) for the complete model.

## Browser support

The Playwright adapter contains launch support for Chromium, Firefox, and WebKit. The project's
release qualification is intentionally more specific than simple implementation availability.

Consult [`docs/support-matrix.md`](docs/support-matrix.md) for the exact browser, operating-system,
CI, and robustness status promised by the current release line.

Do not infer a support commitment only because an enum value or backend launch path exists.

## Security

Web pages, network targets, raw failures, plugins, and caller metadata are distinct trust boundaries.

Important examples:

- WebAgent4J is not a universal SSRF firewall. Callers accepting untrusted URLs must enforce their
  own destination policy.
- `robots.txt` is not automatically enforced by the crawlers.
- Plugins are trusted in-process Java code and are not sandboxed.
- Only diagnostic representations explicitly documented as safe should be logged without an
  application-specific review.
- Recording V1 excludes documented raw workflow/action value channels, but caller identifiers such
  as recording/action IDs remain a verbatim metadata boundary.
- Browser side effects are not automatically retried by wait logic.
- Optional governed execution (`IActionPolicy`/`INetworkPolicy`) lets a caller authorize an action
  or network destination before it runs; every target-bound governed action atomically reproves its
  exact physical target immediately before the backend call and fails closed instead of silently
  retargeting. A configured policy is untrusted, unsandboxed Java code like any plugin, and network
  governance is not a general SSRF firewall. See
  [`docs/governed-execution.md`](docs/governed-execution.md).

See [`SECURITY.md`](SECURITY.md) and
[`docs/security-model.md`](docs/security-model.md) before exposing WebAgent4J to untrusted input.

## Documentation

Start with [`docs/index.md`](docs/index.md). The main references are:

- [Getting started](docs/getting-started.md)
- [Public API map](docs/public-api.md)
- [API stability policy](docs/api-stability.md)
- [Cross-module contracts](docs/contracts.md)
- [Support matrix](docs/support-matrix.md)
- [Security model](docs/security-model.md)
- [Known limitations](docs/limitations.md)
- [Browser lifecycle](docs/browser.md)
- [Semantic locators](docs/locators.md)
- [Wait and stability](docs/wait-and-stability.md)
- [Semantic observation](docs/observation.md)
- [Actions](docs/actions.md)
- [Governed execution](docs/governed-execution.md)
- [Verification](docs/verification.md)
- [Extraction](docs/extraction.md)
- [HTTP crawler](docs/http-crawler.md)
- [Browser crawler](docs/browser-crawler.md)
- [Workflows](docs/workflow.md)
- [Recording](docs/recording.md)
- [Plugins](docs/plugins.md)
- [CLI](docs/cli.md)
- [Testing](docs/testing.md)
- [Hardening evidence](docs/hardening.md)
- [Release process](docs/release.md)
- [Documentation governance](docs/documentation-governance.md)
- [Migration to 1.0](docs/migration-to-1.0.md)
- [Roadmap](docs/roadmap.md)
- [Architecture decision records](docs/adr)

Aggregate Javadoc is generated by:

```bash
./mvnw clean verify
```

The development Javadoc published from `main` is available under `api/latest` when GitHub Pages is
configured. Stable releases must publish immutable version-specific Javadoc according to
[`docs/release.md`](docs/release.md); `api/latest` must not be treated as the reference for an older
released artifact.

## Compatibility

Semantic Versioning compatibility commitments begin with `1.0.0`.

The exact supported Java APIs, SPIs, Maven artifacts, behavioral guarantees, and intentionally
unsupported implementation-public types are defined in
[`docs/api-stability.md`](docs/api-stability.md).

The stable Recording JSON format has its own explicit schema version. Recording schema compatibility
must not be inferred from Java serialization or Java object identity.

## Project status

`1.2.0` is released and is the current stable line (`1.2.x`). Its functional scope is implemented:

- browser lifecycle and semantic location;
- bounded observation;
- verified actions and deterministic waits;
- extraction;
- HTTP and browser crawling;
- deterministic workflows: typed inputs/outputs with guard-aware definite assignment, conditional
  branching (`ifElse`/`ifThen`), and the Validation Report / Execution Plan / Execution Tree
  introspection views (see [Workflows](#workflows) above);
- Recording JSON V1 and offline comparison;
- explicit trusted locator plugins;
- governed execution (`IActionPolicy`/`INetworkPolicy`) with exact verified-target execution across
  every target-bound governed action (including a dedicated `typeSequentially` action for
  per-character input, distinct from replacement `type`/`fill` semantics), decision provenance, and
  transport-bound address pinning for `HttpCrawler`;
- adversarial hardening of cross-module contracts.

`1.1.x` (final release: `1.1.1`) is the previous stable line. Development for the next release
continues on `develop` (`1.3.0-SNAPSHOT`), currently in progress:

- **Recording V2 and Deterministic Replay** — a tree-shaped recording format capturing an Execution
  Plan plus a tree mirroring the Execution Tree, with typed, secret-classified published outputs, and
  a new `io.webagent4j.recording.replay` package validating and deterministically replaying a
  recording's decision path. Structural/decision replay only; real side-effect replay remains out of
  scope for now.
- **Bounded Workflow Loops** — `WorkflowSteps.loop` adds an explicitly-bounded repetition step
  integrated across validation, the Execution Plan (`LOOP { BODY }`, never unrolled), the Execution
  Tree (only actually-executed iterations recorded), and Recording V2/Deterministic Replay.
- **Deterministic Bounded Workflow Parallelism** — `WorkflowSteps.parallel` adds a fixed set of
  2-8 declared, always-run-once branches, executed through a bounded per-step thread pool with
  isolated per-branch state, deterministic definition-order join and failure-selection semantics,
  and fail-closed read-only/observational branch-safety validation - a Workflow `ACTION` step is
  unconditionally forbidden inside a branch; integrated across validation, the Execution Plan, the
  Execution Tree, and Recording V2/Deterministic Replay. Concurrent browser side effects remain out
  of scope for this version.

See [Roadmap](docs/roadmap.md) for the complete, non-normative direction.

## Contributing

Contributions are welcome. Read [`CONTRIBUTING.md`](CONTRIBUTING.md) before opening a pull request.

Use GitHub Issues for reproducible defects and focused proposals, and GitHub Discussions for broader
design discussion where appropriate. General help is covered by [`SUPPORT.md`](SUPPORT.md).

Security issues must be reported privately according to [`SECURITY.md`](SECURITY.md).

## License

WebAgent4J is licensed under the [Apache License 2.0](LICENSE).
