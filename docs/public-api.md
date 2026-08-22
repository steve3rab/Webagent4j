# WebAgent4J Public API

This page is the entry point into WebAgent4J's public API: a map of which module to depend on, which
types are meant to be used directly, and the contracts those types actually guarantee. It is not a
substitute for the generated Javadoc (the exhaustive method-level reference) or the domain guides
linked throughout (the narrative "how it works" documents) - it exists to connect the two.

**Generated Javadoc** (full method-level reference, built from the current head of `main`):
<https://steve3rab.github.io/Webagent4j/api/latest/>

## Status

- **Java 21 or newer** is required everywhere in this project.
- WebAgent4J is **pre-1.0**. Public types are stable enough to build against today, but signatures
  may still change before `1.0.0` as gaps are found. Breaking changes before `1.0.0` are recorded in
  the [changelog](../CHANGELOG.md), not hidden.
- No binary or source compatibility guarantee is made across `0.x` releases yet. Semantic
  versioning applies starting at `1.0.0` (see [API stability](#api-stability)).
- Artifacts are **not currently published to Maven Central** (or any other public repository). Build
  and `./mvnw install` locally, then depend on the resulting local artifacts (see
  [API modules vs. implementation modules](#api-modules-vs-implementation-modules)).
- Browser and HTTP crawling, workflows, schema-V1 recording, and explicit trusted locator plugins
  are implemented. Phase 1.0-B is aligning their shared behavioral contracts; see the
  [cross-module contract matrix](contracts.md), [API stability policy](api-stability.md), and
  [Roadmap](roadmap.md).

## Choosing modules

Depend on the narrowest set of modules that covers what you need. Every row below is a module you
can actually use today - reserved/placeholder modules are listed separately in
[Reserved and placeholder modules](#reserved-and-placeholder-modules), not here.

| Need | Module(s) | Main entry points |
| --- | --- | --- |
| Browser lifecycle (launch, open a page) | `webagent4j-core` (+ `webagent4j-browser-playwright` at runtime) | `WebAgent`, `IBrowser`, `IPage` |
| Semantic element location | `webagent4j-locator-api`, `webagent4j-locator` (pulled in transitively) | `IPage#find()`/`IElement#find()`, `IFind`, `ILocator` |
| Deterministic wait/stability primitives | `webagent4j-wait` | `WaitEngine`, `WaitBudget`, `WaitPolicy`, `IMonotonicClock` |
| Semantic page observation | `webagent4j-observation-api`, `webagent4j-observation` | `IPage#observe()`, `Observation`, `ObservationOptions` |
| Verified browser actions | `webagent4j-action` | `IPage#action()`, `IPreparedAction`, `ActionResult`, `IActionPlan` |
| Read-only postcondition checks | `webagent4j-verification` | `IVerification`, `Verifications`, `VerificationResult` |
| Deterministic data extraction | `webagent4j-extraction-api`, `webagent4j-extraction` | `IPage#extract(...)`/`extractList(...)`/`extractTable(...)`, `ExtractionRequest<T>` |
| HTTP crawling (no browser) | `webagent4j-crawler-api`, `webagent4j-crawler` | `ICrawler`, `HttpCrawler`, `CrawlRequest`, `CrawlResult` |
| Browser crawling (JS-rendered, sessions) | `webagent4j-browser-crawler` (+ `webagent4j-browser-playwright` at runtime) | `IBrowserCrawler`, `BrowserCrawler`, `BrowserCrawlRequest`, `BrowserCrawlResult` |
| Deterministic action orchestration (typed variables, masked secrets, conditions) | `webagent4j-workflow` | `Workflow`, `WorkflowEngine`, `WorkflowSteps`, `WorkflowVariable`, `WorkflowResult` |
| Versioned workflow recording and offline comparison | `webagent4j-recording` | `WorkflowRecorder`, `JsonWorkflowRecordingCodec`, `WorkflowReplayVerifier` |
| Explicit trusted locator plugins | `webagent4j-plugin-api` | `PluginLoader`, `PluginRegistry`, `ILocatorStrategyProvider` |
| Playwright browser adapter | `webagent4j-browser-playwright` (runtime only) | `PlaywrightBrowserProvider` (discovered via `ServiceLoader`, never referenced directly) |
| CLI usage without writing Java | `webagent4j-cli` | `java -jar webagent4j-cli-*.jar ...` (see [Getting started](getting-started.md)) |

`webagent4j-core` and `webagent4j-crawler`/`webagent4j-crawler-api` are independent verticals: the
browser stack (locate/observe/act/verify/extract against a live page) and the HTTP crawler (fetch
and parse HTML at scale, no browser) do not depend on each other and can be used separately.

## API layers

The browser stack is layered so that each layer only talks to the one below it:

```text
Browser backend (Playwright adapter, ServiceLoader-discovered)
    |
    v
Page / Frame / scopes            (webagent4j-browser-api, webagent4j-core)
    |
    v
Locator Engine                   (webagent4j-locator-api, webagent4j-locator)
    |
    v
Wait / Stability                 (webagent4j-wait)
    |
    v
Observation                      (webagent4j-observation-api, webagent4j-observation)
    |
    v
Actions / Verification           (webagent4j-action, webagent4j-verification)
    |
    v
Extraction                       (webagent4j-extraction-api, webagent4j-extraction)
```

`webagent4j-wait` sits underneath locating, verifying, and stabilizing alike - it is the *one*
polling/deadline primitive shared by all three, not three independent timers. See
[Wait and stability](wait-and-stability.md) and [Determinism contract](#determinism-contract).

The HTTP crawler is a separate, parallel stack - it does not sit on top of, or reuse, the browser
layers above:

```text
HTTP (java.net.http.HttpClient)
    |
    v
HTTP Crawler (webagent4j-crawler-api, webagent4j-crawler)
    |
    v
CrawlResult / CrawledPage / CrawlFailure
```

`BrowserCrawler` (Phase 0.7, `webagent4j-browser-crawler`) exists as a deliberately separate,
parallel contract - not a subtype of `ICrawler` and not sharing `CrawlRequest`/`CrawlResult` - see
`docs/browser-crawler.md` for the full contract and rationale, and [HTTP Crawler](#http-crawler)
below for the HTTP-only stack. There is no automatic fallback between the two stacks; the caller
picks the right one for the content (see `docs/browser-crawler.md#when-to-use-browser-crawler`).

## Core principles

These contracts run through every layer above. Each is expanded in its own section below or in the
linked guide; none of them is claimed more strongly here than the code actually guarantees.

- **Deterministic logical behavior** - given the same input and the same live/simulated environment
  responses, resolution order, ranking, dedup decisions, and failure classification are reproducible.
  Wall-clock-dependent values (elapsed durations, exception object identity) are explicitly excluded.
  See [Determinism contract](#determinism-contract).
- **Bounded operations** - every wait (locator resolution, verification polling, action
  stabilization, HTTP crawling) is governed by an explicit, shrinking deadline; nothing polls forever.
- **Explicit ambiguity** - when more than one candidate could be "the" match, WebAgent4J reports a
  structured ambiguity outcome rather than guessing (e.g. "take the first one" or "take DOM order").
  See [Locators](#locators).
- **Stability, not immutability** - `stableFor(...)` means "the *same* identity kept satisfying the
  condition for this long," not "the DOM never changed." See [Wait and stability](#wait-and-stability).
- **Fail-closed on unexpected failures** - a genuine backend/runtime error is never silently turned
  into "not found," `null`, an empty collection, or a fabricated success. See
  [Fail-closed behavior](#fail-closed-behavior).
- **Structured results and failures** - operations return typed result/failure objects with
  diagnostics, not booleans or opaque exceptions to pattern-match on.
- **Provenance/diagnostics** - results carry enough structured context (scope path, redirect chain,
  requested vs. actual value, etc.) to explain *why*, not just *what*.
- **Backend-neutral contracts** - `webagent4j-browser-api`, `webagent4j-locator-api`,
  `webagent4j-observation-api`, `webagent4j-extraction-api`, and `webagent4j-crawler-api` expose no
  Playwright (or other backend-native) type in any public signature.
- **Live re-resolution where applicable** - a semantic reference, a structured scope, or a frame
  criterion is re-resolved against current state on every relevant poll, never resolved once and
  cached. See [Locators](#locators) and [Browser](#browser).

## Domain guides

Each subsection below is a short map into its module - what it is, its entry points, a minimal
example, the semantics that matter most, and how it fails. The linked guide has the full contract;
this page does not repeat it.

### Browser

**What it is:** the backend-neutral browser lifecycle and page/frame contract everything else in the
browser stack builds on. **Module:** `webagent4j-core` (entry point) + `webagent4j-browser-api`
(contracts) + `webagent4j-browser-playwright` (the only current adapter, runtime-only).

**Entry points:** `WebAgent.browser()` (static factory - `WebAgent` has no public constructor) →
`BrowserBuilder` → `IBrowser`. From there, `IBrowser#open(url)`/`newPage()` → `IPage`, and
`IPage#frame()`/`IFrame#frame()` → `IFrameLocator` → `IFrame`.

```java
try (IBrowser browser = WebAgent.browser().playwright().chromium().headless(true).launch()) {
    IPage page = browser.open("https://example.com");
    IFrame checkout = page.frame().named("checkout").single();
    // page and checkout both expose find()/observe()/action()/extract*() - see their own sections
}
```

**Important types:** `IBrowser` (owns the process/context, `AutoCloseable`), `IPage` (a tab: navigate,
`content()`/`screenshot()`/`evaluate()`, plus `find()`/`observe()`/`action()`/`extract*()`/`frame()`),
`IFrame` (the same shape as `IPage`, scoped to one `<iframe>` document boundary - no `screenshot()`,
`content()`, `evaluate()`, or browser-level history), `IFrameLocator` (resolves an `IFrame`; only
`single()`/`tryFind()`, deliberately no `first()`/`all()` - a frame has no ranking dimension, so a
"best match" would really mean "first in DOM order," the exact hidden tie-break frame resolution
never uses), `BrowserOptions`/`BrowserType` (launch config).

**Backend-neutral vs. Playwright:** `webagent4j-browser-api` contains zero Playwright types in any
public signature. `webagent4j-browser-playwright` is discovered at runtime through Java
`ServiceLoader` (`IBrowserProvider`) - `webagent4j-core` has no compile-time dependency on it at
all. Inside `webagent4j-browser-playwright`, every adapter class (`PlaywrightBrowser`,
`PlaywrightPage`, `PlaywrightFrame`, `PlaywrightElement`, ...) is package-private; the only public
type is `PlaywrightBrowserProvider`, the SPI registration class - not something application code
references directly. **Do not** reach for a native Playwright type when an `IBrowser`/`IPage`/
`IFrame`/`IElement` method already covers the need.

**Frame semantics:** an `IFrame` is a re-resolvable semantic handle, not a frozen snapshot - the
`<iframe>` criterion it was built from is re-evaluated against live state on every operation
(navigation, extraction, actions, and every individual poll of a wait), the same guarantee a
structured locator scope has. A removed-then-reinserted `<iframe>` matching the same criterion is
followed transparently; nested frames are resolved strictly inside their own parent's document, so a
same-named frame belonging to a sibling or the top-level page is never matched by accident;
cross-origin iframes resolve the same way same-origin ones do, without weakening Playwright's
default isolation. Ambiguity (two matching frames), not-found/timeout, and a genuine backend/runtime
failure resolving a frame all reuse the same typed exceptions element locators use
(`AmbiguousLocatorException`, `LocatorNotFoundException`) - see [Locators](#locators) for exactly
how those are classified, and [locators.md#frames](locators.md#frames) for the full frame contract
including criteria (`id`/`name`/`title`/`url`), matching modes, and `stableFor(...)`.

**Related guide:** [browser.md](browser.md), [locators.md#frames](locators.md#frames),
[actions.md#frames](actions.md#frames), [extraction.md#frames](extraction.md#frames).

### Locators

**What it is:** the deterministic engine that turns a semantic query (role, accessible name, label,
text, attribute - or an explicit CSS/XPath escape hatch) into exactly zero, one, or many live
elements, with formal, structured ambiguity handling. **Module:** `webagent4j-locator-api`
(contracts, backend-neutral) + `webagent4j-locator` (the engine).

**Entry points:** `IPage#find()`/`IElement#find()`/`IFrame#find()` → `IFind<E>` (role/text/attribute
builders, `within(...)`) → `ILocator<E>` (name/state/timing refinement) → a terminal operation.

```java
IElement login = page.find().button().named("Sign in").visible().enabled().single();
Optional<IElement> maybeBanner = page.find().region().named("Promo").tryFind();
```

**Important types:** `IFind<E>`, `ILocator<E>`, `LocatorDefinition` (the immutable, backend-neutral
record a fluent query ultimately builds), `LocatorConfig`/`LocatorResolutionPolicy` (tuning:
`STRICT`/`BALANCED`/`PERMISSIVE` fuzzy-fallback policy), `LocatorResult`/`LocatorCandidate`/
`LocatorDiagnostics` (structured success/diagnostic output), `AmbiguousLocatorException`/
`LocatorNotFoundException` (structured failures, both implement `io.webagent4j.common.
ILocatorFailure` so callers can classify them without a compile dependency on the locator module).

**`first()` vs. `single()` vs. `all()` vs. `tryFind()`:**

| Method | Zero matches | One match | Multiple compatible matches | Genuine backend/runtime failure |
| --- | --- | --- | --- | --- |
| `first()` | throws `LocatorNotFoundException` | returns it | returns the highest-ranked one (no complaint) | rethrown unchanged |
| `single()` | throws `LocatorNotFoundException` | returns it | throws `AmbiguousLocatorException` if the top two are within the ambiguity margin | rethrown unchanged |
| `all()` | returns an empty list | returns a one-element list | returns every compatible candidate, deterministically ranked (DOM order is only the final tie-breaker, never the primary sort key) | rethrown unchanged |
| `tryFind()` | `Optional.empty()` | `Optional.of(...)` | throws `AmbiguousLocatorException` (never silently narrowed) | rethrown unchanged |

`single()` checks ambiguity on **every individual poll** of a wait, not only the final one - the
moment any poll observes two candidates within the margin, resolution fails immediately, since
ambiguity is a fail-safe condition, never a transiently-pending state the DOM might resolve out of.
`tryFind()` classifies failures through the typed `ILocatorFailure` contract (looking through a
bounded chain of wrapped causes): **only** a genuine not-found outcome becomes `Optional.empty()` -
never advise "just take the first result" to work around an ambiguity failure; that defeats the
whole point of the fail-closed contract.

**Match/failure categories:** `LocatorResolutionStatus` - `RESOLVED`, `AMBIGUOUS`, `UNRESOLVABLE`
(evidence existed but nothing could be confidently identified), `NOT_INTERACTABLE` (a match existed
but violated a requested state constraint), `TIMEOUT` (an explicit bounded wait expired). A backend/
runtime failure is not a value in this enum at all - it is any exception that does not implement (or
wrap) `ILocatorFailure`, and is always rethrown, never reclassified as one of the above.

**Stability:** `stableFor(duration)` requires the *same* resolved candidate identity (and every
requested state constraint) to stay satisfied continuously for the whole window - not "something
satisfied" cumulatively. Detachment, replacement, or a state violation resets the timer to zero. See
[Wait and stability](#wait-and-stability) for the shared mechanism behind this.

**Related guide:** [locators.md](locators.md) (candidates/evidence/scoring, contextual `within(...)`
resolution, mixed scope ordering, frames, custom strategies - all covered in full there).

### Plugins

**What it is:** an explicit, deterministic `ServiceLoader` boundary for trusted providers that
contribute custom `ILocatorStrategy` implementations. **Module:** `webagent4j-plugin-api`, which
depends only on `webagent4j-locator`.

No WebAgent4J component loads locator plugins automatically. The default `new LocatorEngine()` path
always loads zero plugins. An application opts in and composes the result explicitly:

```java
PluginRegistry plugins = new PluginLoader().load();
LocatorEngine locator = new LocatorEngine(plugins.locatorStrategyRegistry());
```

**Important types:** `PluginId`, `PluginVersion`, `PluginDescriptor`,
`ILocatorStrategyProvider`, `PluginLoader`, `PluginRegistry`, `PluginLoadFailureType`,
`PluginLoadFailure`, and `PluginLoadException`. `LocatorEngine(ILocatorStrategyRegistry)` is the
generic locator-side composition constructor and has no dependency on the plugin API.

Providers are initialized in fully qualified class-name order, plugin descriptors are exposed in
plugin-ID order, and custom strategies retain the locator registry's phase/priority/ID ordering.
Loading is fail-closed: a malformed provider, duplicate plugin ID, duplicate strategy ID, built-in
strategy override attempt, or service configuration error prevents the entire registry from being
returned. `PluginVersion` is opaque metadata; it is never used to choose between duplicates or
negotiate compatibility.

Provider code is trusted in-process Java code, not sandboxed or isolated. IDs, versions, and
provider type names are non-sensitive metadata. Structured load failures use framework-owned safe
messages and never expose raw provider exception messages or causes.

**Related guide:** [plugins.md](plugins.md) (provider registration, classloader contract,
deterministic ordering, failure categories, trust boundary, and non-goals).

### Wait and stability

**What it is:** the one deterministic polling/deadline primitive shared by locator resolution,
verification polling, and action stabilization - not three independent timers. **Module:**
`webagent4j-wait`. Not usually driven directly by application code; documented here because its
guarantees explain *why* the other domains behave the way they do.

**Entry points (for a custom probe-based wait, or for understanding what the other domains
configure):** `WaitEngine#await(WaitBudget or Duration, WaitPolicy, IWaitProbe<T>)` →
`WaitResult<T>`.

**Important types:** `WaitEngine` (orchestrator), `WaitBudget` (a monotonic, shareable, saturating
deadline - later sub-operations see a *shrinking* `remaining()`, never a fresh timeout),
`WaitPolicy` (`pollingInterval` + optional `stableFor`), `IWaitProbe<T>`/`WaitSample<T>` (the
caller's side-effect-free reading: `PENDING` or `SATISFIED`), `IMonotonicClock` (never
`Instant.now()`/`System.currentTimeMillis()`, which can jump on an NTP correction or DST change).

**What "stable" means:** a `stableFor` window requires the *same* thing (compared via a caller-
supplied `stabilityKey`, which must be a real, stable identity - never free text like an accessible
name) to stay `SATISFIED` continuously for the whole duration. A different key, or a `PENDING`
sample in between, resets the window to zero even if the cumulative satisfied time would otherwise
have been enough. **Stable does not mean the DOM never changed** - it means the tracked identity/
state specifically kept holding.

**Failure behavior:** a probe that throws propagates immediately - the engine cannot tell "not there
yet" from "ambiguous" from "backend crashed"; only the domain-specific probe (locator/verification/
action) can, and only it decides which exceptions become a retryable `WaitSample.pending()`.
`WaitInterruptedException` preserves thread interrupt status rather than swallowing it.

**Related guide:** [wait-and-stability.md](wait-and-stability.md) (the full per-domain adapter
breakdown: how locator resolution, verification, and action stabilization each plug into this
engine, and the exactly-once backend-execution guarantee it never compromises).

### Observation

**What it is:** a passive, bounded, single capture of "what the page contains" as an immutable,
redacted semantic snapshot - never a live handle back to the DOM. **Module:**
`webagent4j-observation-api` (semantic model, backend SPI) + `webagent4j-observation` (the engine).

**Entry points:** `IPage#observe()`/`observe(ObservationOptions)`, `IFrame#observe(...)` (scoped to
just that frame's own document) → `Observation`.

```java
Observation observation = page.observe();
for (SemanticElement button : observation.buttons()) {
    System.out.println(button.accessibleName());
}
```

**Important types:** `Observation` (the result - `PageMetadata`, ordered `elements()`, convenience
views like `buttons()`/`headings()`/`forms()`/`tables()`, `toCompactText()`, `toJson()`,
`fingerprint()`, `diff(Observation)`), `SemanticElement` (one meaningful element: role, accessible
name, bounded text, state, an `ElementReference` you can resolve or hand to `action()` later),
`ObservationOptions`/`ObservationBudget` (bounds: max elements/depth/text/table rows, hidden-element
and input-value inclusion), `ObservationStatistics` (bounded truncation counts - limits are never
silently applied), `ObservationDiff` (before/after semantic diff).

**Important semantics:** reading an `Observation` never re-queries the live page - it is fully
detached. Redaction is not opt-out: passwords and common token/API-key/credit-card controls are
always redacted even when `includeInputValues(true)` is set, and the raw value never enters the
snapshot, any renderer, or logs. `elements()` reflects meaningful controls/headings/landmarks in
document order, not every DOM node.

**Failure behavior:** timeout and interruption raise observation-specific exceptions
(`ObservationTimeoutException`, `ObservationBackendException`); an applied truncation limit is
always visible in `ObservationStatistics.truncations()`, never silent.

**Related guide:** [observation.md](observation.md) (redaction rules in full, the compact-text/JSON
representations, and the current Phase-3 limitations - shadow DOM and multi-document traversal are
not part of one `page.observe()` call; `IFrame#observe()` is how you observe a specific frame's own
document instead).

### Actions

**What it is:** one explicit, bounded command pipeline per browser interaction, with three distinct
terminal modes - dry-run, plan, and real execution - that all share the exact same target-resolution
and precondition logic so they can never disagree. **Module:** `webagent4j-action`.

**Entry points:** `IPage#action()`/`IFrame#action()` → `IActionBuilder` (click/type/select/check/
hover/scroll/keyboard/navigate/upload/download/waitFor - see [actions.md](actions.md) for the full
list) → `IPreparedAction<R>` (`.precondition(...)`, `.require(...)`, `.expect(...)`, `.timeout(...)`,
`.retry(...)`) → one terminal call.

```java
ActionResult<Void> result = page.action()
        .click(page.find().button().named("Add to cart").reference())
        .expect(Verifications.textVisible("1 item"))
        .execute();
result.throwIfFailed();
```

**Planning vs. validation vs. dry-run vs. execution vs. verification:**

- **`dryRun().execute()`** runs target resolution and precondition evaluation, then returns an
  `ActionResult` with `executionMode() == DRY_RUN` - the backend is never invoked, and stabilization/
  postcondition polling never run either, since both depend on a real side effect having happened.
- **`plan()`** runs the identical resolution+precondition pipeline but returns an immutable,
  inspectable `IActionPlan<R>` instead of a result, with `ActionPlanStatus.READY`/`BLOCKED`.
  `IActionPlan` has no public constructor - it exists only via `plan()`. Calling `plan()` after
  `dryRun()` on the same prepared action throws `IllegalStateException`; the two are mutually
  exclusive terminal modes.
- **`IActionPlan#execute()`** never trusts the snapshot it was built from - it reruns the *entire*
  pipeline (including a structured locator scope's re-resolution and, inside a frame, the frame
  criterion itself) fresh before ever touching the backend. It may be called **at most once per plan
  instance** - a second call, even after the first failed, throws `IllegalStateException` rather than
  risk a second real side effect. `plan.actionId()` always equals `plan.execute().actionId()`.
- **`execute()`** (direct, no `plan()`) runs the same pipeline through to a real backend call,
  exactly once, only after confirming the action's timeout budget has not already been exhausted by
  resolution/preconditions alone.
- **Verification** happens after backend execution, via `expect(...)` postconditions polled by
  `VerificationEngine`/`VerificationPoller` against the *same* shrinking `WaitBudget` the whole
  pipeline shares - see [Verification](#verification).

**Failure model:** `ActionResult<R>` carries a required `ActionStatus` (`SUCCESS`,
`PRECONDITION_FAILED`, `EXECUTION_FAILED`, `VERIFICATION_FAILED`, `TIMEOUT`, `CANCELLED`) and, on
failure, an `ActionFailure(ActionFailureType type, String message, Optional<Throwable> cause)`.
`ActionFailureType`: `TARGET_NOT_FOUND`, `TARGET_AMBIGUOUS`, `PRECONDITION_FAILED`,
`TARGET_NOT_INTERACTABLE`, `ACTION_NOT_SUPPORTED_BY_TARGET`, `BACKEND_FAILURE`, `TIMEOUT`,
`POSTCONDITION_FAILED`, `INTERRUPTED`, `UPLOAD_FAILURE`, `DOWNLOAD_FAILURE`. A target-resolution
failure is classified only through the typed `ILocatorFailure` contract - never by exception class
or message text - so a genuine backend crash is never misreported as `TARGET_NOT_FOUND`.
`ActionResult.executionMode()` (`REAL`/`DRY_RUN`/`NOT_EXECUTED`) tells you whether the backend was
actually invoked, independent of whether the action ultimately succeeded.

**Retry safety:** only a demonstrated, typed `NOT_FOUND` target-resolution outcome is retried
(bounded by `retry(...)`); ambiguity and backend/runtime failures end resolution on the first
attempt. The real backend side effect itself is never retried as part of any wait/poll loop, and
never starts after the action's budget has already expired.

**Related guide:** [actions.md](actions.md) (every supported action, secrets, uploads/downloads,
observation capture, and the full frame-scoped action contract).

### Verification

**What it is:** a deterministic, side-effect-free, pollable condition over current page state -
used both as an action postcondition (`expect(...)`) and standalone. **Module:**
`webagent4j-verification`.

**Entry points:** `Verifications` (static factory for every built-in condition) →
`IVerification` → `VerificationEngine`/`VerificationPoller` (polling), or attach directly via
`IPreparedAction#expect(...)`.

```java
ActionResult<Void> result = page.action()
        .click(page.find().button().named("Continue").reference())
        .expect(Verifications.allOf(
                Verifications.urlContains("/complete"),
                Verifications.textVisible("Order confirmed")))
        .execute();
```

**Important types:** `IVerification` (the condition contract - implement it directly for a custom
condition), `Verifications` (URL/title/element-state/element-data/semantic-diff conditions, plus
`allOf`/`anyOf`/`not` composition), `VerificationResult` (`success`, `type`, `description`,
`expected`, `actual`, `duration`, `timedOut`).

**What the guarantee actually covers:** a verification checks whether its specific condition held at
some polled instant before the timeout - nothing more. It never re-triggers the action that preceded
it; if the action's real effect never manifests as the checked condition, verification times out
rather than retrying the click/type/etc. Composing conditions with `allOf`/`anyOf`/`not` only
aggregates other checked conditions - it adds no independent guarantee about backend state beyond
what each condition itself observes through `IVerificationContext` (`url()`, `title()`, `resolve()`,
`observe()`). Avoid a phrase like "WebAgent4J guarantees the action worked" - the precise claim is
"the specified postcondition(s) held, as observed by polling, within the timeout."

**Failure behavior:** a failed condition is simply not satisfied - `VerificationResult.success() ==
false` - never an exception hiding a mismatch. The first evaluation is immediate; failed conditions
are retried at the configured interval, capped by the remaining shared budget when driven through an
action's postconditions (see [Wait and stability](#wait-and-stability)).

**Related guide:** [verification.md](verification.md).

### Extraction

**What it is:** deterministic, typed data extraction reusing the exact same locator resolution
`find()`/`locate()` already use - never a second, parallel DOM search. **Module:**
`webagent4j-extraction-api` (contracts) + `webagent4j-extraction` (the engine, `ExtractionEngine`).

**Entry points:** `IPage#extract(...)`/`extractList(...)`/`extractTable(...)`, the same three methods
on `IFrame`, and `IElement#extract(...)` for an already-resolved element (no search). All three
page/frame methods are `default` methods that throw `UnsupportedOperationException` unless a backend
overrides them - the Playwright adapter does.

```java
ExtractionResult<Integer> stock = page.extract(
        ExtractionRequest.text(LocatorDefinition.forRole(ElementRole.HEADING).named("In stock"))
                .convert(IValueConverter.toInteger())
                .validate(IExtractionValidator.range(0, 10_000)));
```

**Pipeline:** resolve source (`locateSingle` for `extract`/`extractTable`, `locateAll` for
`extractList` - the exact same ambiguity rules as [Locators](#locators)) → read raw value (`TEXT`/
`ATTRIBUTE`/`VALUE`) → convert → validate → `ExtractionResult<T>`.

**Important types:** `ExtractionRequest<T>` (immutable; static factories `text(source)`/
`attribute(source, name)`/`value(source)`, then fluent `.convert(...)`/`.validate(...)`),
`ExtractionResult<T>` (converted `value()`, pre-conversion `rawValue()` for scalar reads,
`ExtractionProvenance`), `ExtractedTable`/`ExtractedRow` (`cell(row, col)`/`cell(row, headerName)`),
`IValueConverter<T>` (built-ins: `identity()`, `toInteger()`, `toLong()`, `toBigDecimal()`,
`toBoolean()` - strict `"true"`/`"false"` only, `toLocalDate()`/`toLocalDate(formatter)`),
`IExtractionValidator<T>` (built-ins: `nonBlank()`, `range(min, max)`, `matches(Pattern)`,
`predicate(...)`).

**Converter is mandatory, never optional:** every `ExtractionRequest` always has a converter -
`text()`/`attribute()`/`value()` start with `identity()`. A converter that returns `null` is itself
treated as an `ExtractionConversionException` - `ExtractionResult` can never carry a `null` `value()`
(enforced at construction).

**Failure taxonomy** (all extend `AExtractionException`, all thrown rather than substituting a
default): `ExtractionAttributeMissingException` (element exists, attribute doesn't - distinct from
the element being absent, which is still `LocatorNotFoundException`), `ExtractionConversionException`
(raw value didn't deterministically convert), `ExtractionValidationException` (converted value failed
a rule, always after conversion already succeeded). Not-found and ambiguity are never reinterpreted -
they surface as the same `LocatorNotFoundException`/`AmbiguousLocatorException` any other locator
operation raises.

**DOM order vs. rank order:** `extractList` explicitly sorts by DOM order
(`LocatorCandidate#domOrder()`), even though `locateAll`'s own contract is deterministic *rank*
order, not necessarily DOM order - the two are different orderings used for different purposes, and
extraction always uses DOM order for a requested collection.

**Nested tables:** `extractTable` anchors every selector to *direct* children at each level (headers,
rows, cells), so a table nested inside one of this table's own cells never contributes its own
headers/rows/cells to the outer result.

**Related guide:** [extraction.md](extraction.md) (provenance/scope-path detail, frame-scoped
extraction, element-direct extraction, and the current out-of-scope list: crawling, OCR, AI schema
inference, "visual tables").

### HTTP Crawler

**What it is:** a deterministic, sequential, backend-neutral HTTP crawler - no browser, no
JavaScript execution, no AI. Fetches and parses HTML at scale over plain `java.net.http.HttpClient`.
It does not sit on top of, reuse, or replace the browser stack above - the two are independent
verticals. For JavaScript-rendered content this HTTP-only crawler cannot see, see `BrowserCrawler`
(Phase 0.7, `webagent4j-browser-crawler`, `docs/browser-crawler.md`) - a deliberately separate,
parallel contract, not a subtype of `ICrawler`. **Module:** `webagent4j-crawler-api` (contracts) +
`webagent4j-crawler` (the engine).

**Entry points:** `ICrawler#crawl(CrawlRequest)` - `HttpCrawler implements ICrawler`, with a no-arg
production constructor. This is the entire public surface: one blocking call, no
`CompletableFuture`, no callback, no cancellation token in this phase.

```java
CrawlRequest request = CrawlRequest.builder()
        .seed("https://example.com/")
        .maxDepth(2)
        .maxPages(50)
        .build();

CrawlResult result = new HttpCrawler().crawl(request);
for (CrawledPage page : result.pages()) {
    System.out.println(page.depth() + "  " + page.finalUrl() + "  " + page.title());
}
```

**Pipeline:** seed → normalization → scope policy (host/domain/scheme) → frontier (BFS) → fetch →
redirects/retries → HTML parsing (jsoup) → link discovery → deduplication → `CrawlResult`.

**Important types:** `CrawlRequest` (immutable, fully validated at construction - every
misconfiguration raises `IllegalArgumentException` immediately, never mid-crawl), `CrawlResult`
(`pages()`, `failures()`, `statistics()`, `rejectedUrls()`, `terminationReason()`), `CrawledPage`,
`CrawlFailure`/`CrawlFailureType`, `CrawlStatistics`, `DiscoveredLink`, `CrawlTerminationReason`
(`COMPLETED`, `MAX_PAGES_REACHED`, `FATAL_ERROR`). Extension points: `IUrlNormalizer`,
`ICrawlScopePolicy`, `ICrawlDeduplicator` (all backend-neutral ports in `crawler-api`).

**`CrawlRequest` options** (defaults in parentheses): `maxDepth` (3, seed = depth 0), `maxPages`
(100), `sameHostOnly` (`true`), `includeSubdomains` (`false`, true subdomains only - no lookalike-
domain match), `allowedSchemes` (`http`, `https` - always a subset, rejected otherwise at
construction), `requestTimeout` (10s per attempt), `maxResponseBytes` (5,000,000, enforced while
streaming), `maxRedirects` (5 per task), `retryPolicy`/`retryableStatusCodes` (429/500/502/503/504;
4xx never retried by default), `allowedContentTypes` (`text/html`, `application/xhtml+xml`),
`queryParameterPolicy`, `includeUrlPatterns`/`excludeUrlPatterns`, `failFast` (`false`).

**Two identity concepts, deliberately not conflated:** *discovery identity* (seeds and `<a href>`/
`<area href>` links only, never a redirect hop) drives `CrawlStatistics#discoveredUrls()`; *fetch
identity* (every real HTTP request - a task's own URL and every redirect hop it follows) drives
`fetchedUrls()` and is exactly what `maxPages` bounds, claimed immediately before every real request
via one central gate. No general mathematical relationship holds between `discoveredUrls`,
`fetchedUrls`, `successfulPages`, and `failedUrls` - each is defined independently (a `CrawlFailure`
can be recorded for a URL that was never actually fetched, for example). See
[http-crawler.md](http-crawler.md) for the exact definitions and why no such invariant is claimed.

**Failure/termination semantics:** `CRAWL_LIMIT_REACHED` and `ALREADY_FETCHED` are decided *before*
any request is sent (`attempts() == 0` - the only failure types allowed to have zero attempts; every
other type requires `attempts() >= 1`) and are treated as ordinary, expected outcomes of the crawl's
own graph, never a backend problem - `failFast` never turns either into `FATAL_ERROR`. `ALREADY_FETCHED`
is a structured signal, not a page cache: a redirect converging on an identity another task already
fetched is never fetched again, but no second `CrawledPage` is fabricated from a cache either.
`MAX_PAGES_REACHED` is a normal termination reason, not a fatal error. `BACKEND_FAILURE` is reserved
for a genuine, opaque fetcher exception - never silently reclassified. `HTTP_CLIENT_ERROR`/
`HTTP_SERVER_ERROR` are real HTTP responses; `UNEXPECTED_HTTP_STATUS` covers a status outside every
other classified range (e.g. `304`) so it is never folded into `HTTP_SERVER_ERROR` by elimination.

**Explicitly not implemented in this phase:** no JavaScript execution, no browser rendering, no
`robots.txt` enforcement, no distributed or high-concurrency crawling (intentionally sequential), no
automatic browser fallback.

**Related guide:** [http-crawler.md](http-crawler.md) (redirect handling, retry semantics, response-
size protection, and the precise determinism/`Throwable`-equality contract).

### Workflows

**What it is:** a deterministic, sequential orchestration layer over `webagent4j-action` - an
immutable, reusable `Workflow` definition of typed-variable, optionally-secret, optionally-guarded
steps, executed by a stateless `WorkflowEngine` into a structured, secret-masked `WorkflowResult`.
Not a general programming language: no loops, no branching graph, no hidden retries. **Module:**
`webagent4j-workflow` (depends only on `webagent4j-action`).

```java
WorkflowVariable<String> username = WorkflowVariable.publicValue("username", String.class);
WorkflowVariable<String> password = WorkflowVariable.secret("password");

Workflow login = Workflow.builder("login")
        .requiredInput(username)
        .requiredInput(password)
        .step(WorkflowSteps.action("type-username",
                vars -> page.action().type(usernameField, vars.require(username))))
        .step(WorkflowSteps.action("type-password",
                vars -> page.action().typeSecret(passwordField, Secret.of(vars.require(password)))))
        .step(WorkflowSteps.action("sign-in",
                vars -> page.action().click(signInButton).expect(urlContains("/dashboard"))))
        .build();

WorkflowResult result = new WorkflowEngine().execute(login, WorkflowInputs.builder()
        .put(username, "alice")
        .put(password, "hunter2")
        .build());
result.throwIfFailed();
```

**Public types:**

| Type | Purpose |
| --- | --- |
| `Workflow`, `Workflow.Builder` | immutable, reusable workflow definition; structural validation only, no side effects |
| `WorkflowEngine` | stateless executor; one private, isolated session per `execute()` call |
| `WorkflowId`, `WorkflowStepId` | stable, caller-chosen identity (never randomly generated) |
| `WorkflowVariable<T>` | typed, optionally-secret variable key; `publicValue`/`secret` factories |
| `IWorkflowVariables` | read-only variable view handed to conditions and action factories |
| `WorkflowVariableMissingException` | thrown by `IWorkflowVariables#require` for a missing variable |
| `WorkflowInputs`, `WorkflowInputs.Builder` | immutable, explicit execution inputs; secret-masked `toString()` |
| `WorkflowOutputs` | immutable produced-variable set backing `WorkflowResult#output` |
| `IWorkflowCondition`, `WorkflowConditions` | built-in conditions (`exists`, `notExists`, `equals`, `notEquals`, `isTrue`, `isFalse`, `not`, `allOf`, `anyOf`); `IWorkflowCondition` is also a trusted Java extension point, handled defensively by the engine (see [workflow.md#conditions](workflow.md#conditions)) |
| `WorkflowConditionResult` | safe, structured outcome of one condition evaluation |
| `IWorkflowStep`, `WorkflowSteps` | `sealed` step contract with no custom-implementation extension point; `action`/`assign` factories (no public constructor) |
| `IWorkflowActionFactory<R>` | single-use-per-execution preparation factory: `IWorkflowVariables -> IPreparedAction<R>` |
| `WorkflowStatus`, `WorkflowStepStatus`, `WorkflowStepType` | terminal-outcome and category enums |
| `WorkflowFailureType`, `WorkflowFailure` | stable failure taxonomy and safe, redacted failure detail (never a raw `Throwable`) |
| `WorkflowActionSummary` | safe projection of an `ActionResult` (`ActionId`/`ActionType`/`ActionStatus`/`ActionExecutionMode` only) |
| `WorkflowStepResult`, `WorkflowResult` | structured per-step and overall execution outcome |
| `WorkflowFailedException` | optional `throwIfFailed()` projection, mirroring `ActionFailedException` |

**Secret masking:** explicit typed retrieval (`IWorkflowVariables#require`/`#find`,
`WorkflowResult#output`) always returns the real value; every incidental, framework-owned
rendering (every `toString()`, every condition `describe()`) masks a secret as `***`, centrally,
with longest-first redaction for overlapping secret values, always applied before any
bounding/truncation. `WorkflowInputs`/`WorkflowOutputs` rendering is cross-field: a known secret's
raw text is redacted everywhere it appears, even inside a value from a field declared public. See
[workflow.md#secret-masking](workflow.md#secret-masking) for the full contract and its limits.

**Execution semantics:** strictly sequential, single-threaded, fail-fast only - the first failed
step stops the workflow and marks every later step `NOT_RUN`. No workflow-level retry (a failed
action may already have a real side effect), no workflow-wide timeout, no cancellation in this
phase.

**Explicitly not implemented in this phase:** loops, branching graphs, parallel execution,
persistence, scheduling, an expression/DSL language, and AI/MCP integration. Recording is a
separate module - see [Recording](#recording) below.

**Related guide:** [workflow.md](workflow.md) (full architecture, the complete secret-masking
contract, condition semantics, and determinism guarantee).

### Recording

**What it is:** a deterministic, versioned recording that excludes raw workflow values and
preserves engine-redacted diagnostics from one `WorkflowResult`
(`WorkflowRecorder`), canonical JSON encoding/decoding (`IWorkflowRecordingCodec`), and a pure,
offline structured comparison between a recording and a new execution's `WorkflowResult`
(`WorkflowReplayVerifier`). A recording is data, not a program - it has no `execute()` method and
cannot replay itself; there is no automatic live browser replay in this phase. **Module:**
`webagent4j-recording` (depends on `webagent4j-workflow`; `jackson-databind` internally, never
exposed in a public signature).

```java
WorkflowResult result = new WorkflowEngine().execute(login, inputs);

WorkflowRecorder recorder = new WorkflowRecorder();
WorkflowRecording recording = recorder.record(new RecordingId("run-42"), Instant.now(), result);

IWorkflowRecordingCodec codec = new JsonWorkflowRecordingCodec();
String json = codec.encode(recording);                 // canonical, deterministic JSON
WorkflowRecording decoded = codec.decode(json);         // throws RecordingFormatException if malformed

// Later, a new independent execution of the same workflow:
WorkflowResult newResult = new WorkflowEngine().execute(login, newInputs);
WorkflowReplayResult replay = new WorkflowReplayVerifier().verify(decoded, newResult);
if (!replay.matches()) {
    System.out.println(replay.mismatches());
}
```

**Public types:**

| Type | Purpose |
| --- | --- |
| `RecordingId` | caller-supplied non-sensitive recording identity, persisted verbatim and ignored by replay comparison |
| `RecordingSchemaVersion` | closed, numbered JSON schema version enum (`V1` only in this phase) |
| `WorkflowRecording` | immutable top-level recording: `schemaVersion`, `recordingId`, `capturedAt`, `workflowId`, `status`, `steps`, `failure`; construction enforces the fail-fast execution shapes described in [recording.md#recording-validity](recording.md#recording-validity-a-recording-represents-one-fail-fast-execution) |
| `RecordedWorkflowStep` | safe per-step projection, mirroring `WorkflowStepResult`'s invariants |
| `RecordedCondition`, `RecordedAction`, `RecordedFailure` | safe per-field projections of `WorkflowConditionResult`/`WorkflowActionSummary`/`WorkflowFailure` |
| `WorkflowRecorder` | stateless capture: `WorkflowResult -> WorkflowRecording`; never calls `WorkflowResult#output` |
| `IWorkflowRecordingCodec`, `JsonWorkflowRecordingCodec` | encode/decode to canonical JSON; strict decoding, no fallback; `schemaVersion` uses an exact-range numeric check, never a truncating conversion |
| `RecordingFormatException` | thrown by decoding; messages are fixed and framework-owned and never echo the offending input; `getCause()` is always `null`; constructors are package-private (catch it, don't construct it) |
| `WorkflowReplayVerifier` | stateless, pure, synchronous structured comparison: `(WorkflowRecording, WorkflowResult) -> WorkflowReplayResult` |
| `WorkflowReplayResult`, `WorkflowReplayMismatch`, `WorkflowReplayMismatchType` | every difference found, in deterministic order; `matches()` is derived from `mismatches().isEmpty()` |

**Secret-safety boundary:** `WorkflowRecorder` never reads raw workflow inputs or output values,
`WorkflowResult#output(WorkflowVariable)`, `ActionResult#value()`, observations, diagnostics, raw
`Throwable` data, or the secret registry. It preserves condition and failure diagnostics already
redacted by `WorkflowEngine`. Metadata identifiers such as `RecordingId` and `ActionId` are
persisted verbatim, are not sanitized by Recording, and must be non-sensitive.

**Replay semantics:** `WorkflowReplayVerifier#verify` never re-executes anything - the caller
supplies a `WorkflowResult` from its own new `WorkflowEngine#execute` call. Comparison never fails
fast (every mismatch is collected, in deterministic order) and deliberately ignores `RecordingId`,
`capturedAt`, `ActionId` (non-semantic correlation metadata), a condition's description
text, and a failure's `safeMessage`/underlying exception type name - see
[recording.md#ignored-fields-and-why](recording.md#ignored-fields-and-why) for the full rationale.

**Explicitly not implemented in this phase:** automatic live replay of recorded browser actions,
action recreation, persistence, a plugin SPI, or AI/MCP integration.

**Related guide:** [recording.md](recording.md) (full architecture, JSON schema, and replay
semantics).

## Error and result semantics

WebAgent4J distinguishes several situations that a less explicit API might collapse into one
generic exception or boolean. This table is a map across every domain currently implemented; each
domain's own section below has the full detail.

| Situation | Public representation | Recoverable? | Notes |
| --- | --- | --- | --- |
| Configuration/programming error (bad builder input) | `IllegalArgumentException`/`IllegalStateException` at construction | Programming/configuration error | Fails immediately, never mid-operation - e.g. `CrawlRequest`, `LocatorConfig` |
| Target/source not found | `LocatorNotFoundException` (thrown) or `Optional.empty()` from `tryFind()` | Normally expected | Never conflated with a backend failure |
| Ambiguity (more than one valid match) | `AmbiguousLocatorException` | Normally expected, policy-dependent | Never resolved by picking DOM order or "the first one" |
| Timeout | `TIMEOUT` status/outcome, or a thrown timeout-flavored exception depending on the API | Normally expected | A bounded wait's deadline passed without a satisfied result |
| Instability (state kept changing during `stableFor`) | Same as timeout (the wait never became stable in time) | Normally expected | Stability resets on any change to the tracked identity/state - see [Wait and stability](#wait-and-stability) |
| Failed action precondition | `ActionFailureType.PRECONDITION_FAILED` in `ActionResult`/`ActionFailure` | Normally expected | Reported as structured result data, not thrown |
| Failed action postcondition (`expect(...)`) | `ActionStatus.VERIFICATION_FAILED` with `ActionFailureType.POSTCONDITION_FAILED` | Normally expected | The action itself already ran; verification is what failed |
| Extraction conversion failure | `ExtractionConversionException` | Normally expected (bad/unexpected page data) | Raw value retained for diagnosis |
| Extraction validation failure | `ExtractionValidationException` | Normally expected | Distinct from a conversion failure |
| Missing HTML attribute during extraction | `ExtractionAttributeMissingException` | Normally expected | Distinct from the element itself being absent |
| HTTP client error (4xx) | `CrawlFailureType.HTTP_CLIENT_ERROR` | Normally expected | Never retried by default |
| HTTP server error (5xx, retries exhausted) | `CrawlFailureType.HTTP_SERVER_ERROR` | Normally expected | Retried per `RetryPolicy` first |
| Unexpected HTTP status (e.g. `304`, unhandled `1xx`) | `CrawlFailureType.UNEXPECTED_HTTP_STATUS` | Normally expected | Never folded into the server-error bucket |
| Crawl page budget reached | `CrawlFailureType.CRAWL_LIMIT_REACHED`, `CrawlTerminationReason.MAX_PAGES_REACHED` | Policy-dependent (the caller configured `maxPages`) | `attempts == 0` - no request was ever sent for that URL |
| Redirect converged on an already-fetched URL | `CrawlFailureType.ALREADY_FETCHED` | Normally expected | Not a page cache; a structured signal instead |
| Redirect loop / too many redirects | `CrawlFailureType.REDIRECT_LOOP` / `CrawlFailureType.TOO_MANY_REDIRECTS` | Normally expected | Detected on normalized identity |
| Genuine backend/runtime failure (browser crash, disconnected client, opaque exception) | `BACKEND_FAILURE` (action), `CrawlFailureType.BACKEND_FAILURE` (crawler), or the exception is simply rethrown unwrapped (locator/extraction) | Backend/runtime failure | **Never** silently reinterpreted as "not found" - see [Fail-closed behavior](#fail-closed-behavior) |

"Recoverable?" is deliberately not a single yes/no column: whether a caller *should* retry, and
whether retrying is *safe*, depends on the specific situation - see
[Fail-closed behavior](#fail-closed-behavior) for what "backend/runtime failure" means in practice,
and each domain section for the exact retry rules that apply to it.

## Determinism contract

WebAgent4J's determinism guarantee is precise, not absolute. Read it as two separate claims:

**A. Logical determinism** (guaranteed, given the same inputs and environment responses):

- locator candidate discovery, scoring, and the final ranking/ambiguity decision;
- which strategies run and in what order;
- crawl frontier traversal order, URL normalization, and deduplication decisions;
- redirect-following and retry decisions;
- failure classification and failure/result ordering;
- structured diagnostics content (for a fixed environment state).

**B. Environment-dependent values** (never claimed to be reproducible):

- elapsed durations measured against the production, real `IMonotonicClock` (a fixed/fake clock
  makes these reproducible too, which is exactly what the crawler's own determinism tests do - see
  [http-crawler.md](http-crawler.md#time-and-determinism));
- anything read from a live page or a live HTTP response - the actual DOM, the actual bytes returned;
- backend timing and scheduling;
- `Throwable` object identity, message object, and stack trace carried inside a structured failure
  (`CrawlFailure#cause()` in particular) - two runs that fail with logically identical causes are
  never guaranteed to be `equals()` because of this, even though every other field of the failure is
  reproducible;
- external network/browser behavior in general.

WebAgent4J never claims "the same input always produces an identical Java object graph." It claims
the *logical* decisions above are reproducible, and is explicit about the specific fields that are
not part of that guarantee.

## Fail-closed behavior

An unexpected backend or runtime failure is never silently converted into a normal, "nothing to see
here" outcome. Concretely, WebAgent4J never turns an opaque failure into:

- a typed `NOT_FOUND` result;
- `null`;
- an empty collection;
- a fabricated HTTP status or a fabricated success;
- an endless retry loop.

This is why, for example, `tryFind()` (locators, frame lookup) returns `Optional.empty()` **only**
for a genuine, typed "not found" outcome - an `AmbiguousLocatorException`, or any backend/runtime
failure, is always rethrown, never swallowed into an empty `Optional`. The same principle shows up
per domain: `HTTP_CLIENT_ERROR`/`HTTP_SERVER_ERROR` are real HTTP responses, never confused with
`BACKEND_FAILURE` (an opaque fetcher exception); `ActionFailureType.BACKEND_FAILURE` is never
reported as `TARGET_NOT_FOUND`; `CrawlFailureType.BACKEND_FAILURE` is never silently reclassified.

**`tryFind()` is not "swallow any exception."** It classifies the actual failure through a typed
contract (`ILocatorFailure` for element/frame lookups) that looks through a bounded chain of wrapped
causes; only a failure that *is*, or *wraps*, a typed not-found outcome becomes `Optional.empty()`.
Everything else - ambiguity, a disconnected backend, a browser crash - propagates as a real,
unwrapped exception.

## API stability

- WebAgent4J is **pre-1.0**. Necessary breaking cleanups remain possible until `1.0.0` and are
  recorded in the [changelog](../CHANGELOG.md) and, when migration is non-trivial, the
  [migration guide](migration-to-1.0.md).
- Starting with `1.0.0`, supported Java APIs, SPIs, and Maven coordinates follow Semantic
  Versioning. Patch, minor, and major compatibility rules are defined in the
  [API stability policy](api-stability.md).
- Java visibility is not the support boundary. Documented consumer API and the explicit SPI list
  are supported; `.internal` packages, examples, build/test modules, empty placeholder artifacts,
  and the runtime-only concrete Playwright provider are not consumer API.
- Native Java serialization is not a supported persistence or compatibility format. Recording JSON
  has a separate, explicit `schemaVersion` policy and remains V1.
- There are currently no formally `@Deprecated` public APIs in the implemented modules; if/when one
  is introduced, it will be documented here and in the changelog rather than silently removed.
- Prefer the entry points this document lists as "primary API" over reaching into `internal`
  packages, backend-implementation classes, or types marked below as internal-by-design. Those are
  not covered by this stability discussion at all.

## API modules vs. implementation modules

Several domains split into an **API module** (backend-neutral contracts and value types, safe to
depend on from application code) and an **implementation module** (the deterministic engine and/or a
concrete backend adapter):

```text
browser-api      <->  browser-playwright   (Playwright is currently the only adapter)
extraction-api   <->  extraction
crawler-api      <->  crawler
locator-api      <->  locator
observation-api  <->  observation
```

Depend on the *-api module directly only if you are writing a custom backend/strategy/extension
point; for ordinary application code, depend on `webagent4j-core` (which pulls in the browser stack
transitively) or `webagent4j-crawler` (which pulls in `webagent4j-crawler-api`), and let Maven
resolve the rest.

Minimal `pom.xml` for browser automation:

```xml
<dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>io.webagent4j</groupId>
      <artifactId>webagent4j-bom</artifactId>
      <version>0.1.0-SNAPSHOT</version>
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

Minimal `pom.xml` for HTTP crawling only (no browser, no Playwright dependency at all) - the BOM
manages `webagent4j-crawler`'s version the same way it does every other module:

```xml
<dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>io.webagent4j</groupId>
      <artifactId>webagent4j-bom</artifactId>
      <version>0.1.0-SNAPSHOT</version>
      <type>pom</type>
      <scope>import</scope>
    </dependency>
  </dependencies>
</dependencyManagement>

<dependencies>
  <dependency>
    <groupId>io.webagent4j</groupId>
    <artifactId>webagent4j-crawler</artifactId>
  </dependency>
</dependencies>
```

Artifacts are not yet published to a public repository - run `./mvnw install` locally first (see
[Status](#status)).

## Reserved and placeholder modules

These modules exist in the reactor but are **intentionally empty** - reserved boundaries, not usable
public API and no longer managed by the BOM. Do not add a dependency on them expecting functionality:

| Module | Reserved for |
| --- | --- |
| `webagent4j-http` | A future standalone non-browser HTTP transport boundary (the HTTP crawler currently has its own fetcher and does not need this) |
| `webagent4j-storage` | A future persistence boundary |

`webagent4j-crawler` graduated from this list to a real implementation in Phase 0.6,
`webagent4j-workflow` graduated in Phase 0.8 (see [Workflows](#workflows) and
[workflow.md](workflow.md)), and `webagent4j-recording` graduated in Phase 0.9-A (see
[Recording](#recording) and [recording.md](recording.md)). `webagent4j-plugin-api` graduated in
Phase 0.9-B (see [Plugins](#plugins) and [plugins.md](plugins.md)); the two modules still listed
above remain reserved. See
[modules.md](modules.md) for the full dependency graph and [roadmap.md](roadmap.md) for what each
future phase is expected to deliver.

`webagent4j-testing` also currently has **no source code at all** (only its `pom.xml` exists) - it is
not a supported shared test-fixture library and is not managed by the BOM. Do not depend on it
expecting fixtures today. See [Testing](testing.md) for how this project tests itself in the
meantime.

`webagent4j-bom` (dependency-version alignment only), `webagent4j-integration-tests`, and
`webagent4j-robustness-tests` are build/test infrastructure, not application-facing API either.
