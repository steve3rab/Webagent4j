# Cross-module contracts

This is the normative consistency matrix for supported WebAgent4J API and SPI. It defines framework-wide meanings and calls out intentional differences between domains. Domain guides remain authoritative for domain-specific details.

## Common terminology

| Term | Meaning |
| --- | --- |
| Success | The operation's documented objective completed; optional diagnostics may still be absent. |
| Expected absence | The requested entity is demonstrably not present. The representation is domain-specific. |
| Ambiguous | More than one candidate satisfies a contract requiring one. Ambiguity never becomes absence. |
| Backend failure | Unexpected failure of a browser, transport, provider, or adapter. It never becomes fabricated absence/success. |
| Timeout | A configured deadline was not satisfied. It is distinct from interruption/cancellation and backend failure. |
| Interruption | Caller-thread interruption. Where handled, the interrupt flag is preserved. |
| Deterministic | Logical ordering/classification is reproducible for the same inputs/environment responses; elapsed real time need not be. |

## Result and failure boundaries

| Domain | Success | Expected non-success | Unexpected failure |
| --- | --- | --- | --- |
| Wait | `WaitResult(SUCCESS)` with value | `TIMED_OUT`, optionally retaining last informational value | Probe runtime failure propagates; interruption becomes `WaitInterruptedException` |
| Locator | `LocatorResult` / resolved element(s) | typed not-found or ambiguity according to terminal operation | backend/runtime failure propagates |
| Verification | successful `VerificationResult` | mismatch or timeout result | callback/implementation failure follows verification contract |
| Action | `ActionResult` with `SUCCESS` | structured non-success status/failure | supported pipeline failures classified; retained raw causes are sensitive |
| Extraction | successful `ExtractionResult` | typed conversion/validation/attribute failure | opaque runtime/backend failure propagates |
| HTTP crawler | `CrawlResult` with pages | per-URL `CrawlFailure`; siblings normally continue | fetcher/backend failures become structured crawler failures, never fake responses |
| Browser crawler | `BrowserCrawlResult` with pages | browser-specific failure/cancellation | backend failure uses browser-crawler taxonomy; fail-fast is explicit |
| Workflow | `COMPLETED` trace | first runtime failure then `NOT_RUN`; preflight failure all `NOT_RUN` | supported runtime failures become safe structured workflow failures |
| Recording | valid recording / comparison result | malformed data rejected; mismatches are data | codec defects follow codec exception boundary |
| Plugins | complete immutable registry | all-or-nothing `PluginLoadException` | provider/strategy behavior follows trusted SPI boundary |

A universal `Result<T,E>` is intentionally not introduced because each domain carries different safety information.

## Action outcome matrix

The following shapes are the only valid combinations when status, execution mode, and failure type are all represented:

| `ActionStatus` | `ActionExecutionMode` | Failure |
| --- | --- | --- |
| `SUCCESS` | `REAL` or `DRY_RUN` | absent |
| `PRECONDITION_FAILED` | `NOT_EXECUTED` | `PRECONDITION_FAILED` |
| `EXECUTION_FAILED` | `NOT_EXECUTED` | `TARGET_NOT_FOUND`, `TARGET_AMBIGUOUS`, or `BACKEND_FAILURE` |
| `EXECUTION_FAILED` | `REAL` | `TARGET_NOT_INTERACTABLE`, `ACTION_NOT_SUPPORTED_BY_TARGET`, `BACKEND_FAILURE`, `UPLOAD_FAILURE`, or `DOWNLOAD_FAILURE` |
| `VERIFICATION_FAILED` | `REAL` | `POSTCONDITION_FAILED` |
| `TIMEOUT` | `NOT_EXECUTED` or `REAL` | `TIMEOUT` |
| `CANCELLED` | `NOT_EXECUTED` or `REAL` | `INTERRUPTED` |

`NOT_EXECUTED` cancellation means interruption was observed before backend invocation. `REAL` cancellation means the backend was already invoked or a side effect may have started. Cancellation is never `DRY_RUN`.

Planning has no execution mode: interruption during `plan()` yields `ActionPlanStatus.BLOCKED` with `INTERRUPTED`, no backend invocation, and the caller thread remains interrupted.

## Workflow and recording trace shape

Every accepted workflow result/recording contains at least one step.

- `COMPLETED`: every step is `SUCCEEDED` or `SKIPPED`.
- Preflight `FAILED`: every step is `NOT_RUN`; the overall failure is a preflight input failure and has no step ID.
- Runtime `FAILED`: zero or more `SUCCEEDED`/`SKIPPED`, exactly one matching `FAILED`, then only `NOT_RUN`.
- A failed step publishes no output and its failure identifies that step.
- Only `ACTION_FAILED` carries an `ActionFailureType` and it obeys the action matrix above.
- Recording schema V1 preserves these invariants without adding live replay semantics.

## Time and budgets

- Timeouts and elapsed durations use monotonic time for deadline arithmetic. Wall-clock time is only for absolute audit timestamps where documented.
- `WaitBudget` supports a zero allowance for exactly the documented immediate-probe behavior and uses rollover-safe elapsed arithmetic.
- Locator resolution uses one bounded wait budget; dynamic scopes are re-resolved within that budget rather than starting nested full-timeout waits.
- Action target resolution, stabilization, and postconditions consume one shared action budget. A backend side effect is not started after that budget is already expired.
- An already-running backend operation is not claimed to be forcibly interruptible merely because the Java-side deadline expires.
- Verification's standalone fixed-duration `awaitAll` API may give each condition its own timeout; the action pipeline uses the shared-budget overload.
- Browser-crawler navigation and stability share one navigation budget. Post-stability observation/title/URL calls are separate operations and are documented limitations where no backend-native timeout is available.

## Retry and exactly-once safety

- Read-only probes may be polled repeatedly.
- Action target resolution retries only a demonstrated typed `NOT_FOUND` according to explicit retry policy.
- Ambiguity and opaque backend/runtime failures are not retried as absence.
- The actual backend side effect executes at most once per action execution path.
- `IActionPlan.execute()` is single-use; a second call fails rather than risking a repeated side effect.
- Workflow execution adds no hidden retry around steps or actions.
- Plugin callbacks are not automatically retried.

“At most once” is intentionally narrower than “known to have completed once”: a backend can fail after a side effect may have started, which is why `REAL` execution mode matters.

## Resource ownership

| Resource | Owner | Rule |
| --- | --- | --- |
| Browser/page/frame live resources | caller that creates/receives them | close the browser; pages/resources inside its context are closed with it |
| Browser-crawler page | `BrowserCrawler` | created lazily and closed in `finally` on every terminal path |
| Browser passed to browser crawler | caller by default | crawler closes it only with explicit ownership-transfer option |
| Workflow browser/action resources | application/factory | workflow does not create, cache, or close them |
| Plugin class loader | caller | loader does not close or globally cache it |
| HTTP response stream | fetcher implementation | consumed/closed inside the fetch boundary; no open stream escapes normal result |
| Immutable values/results | none | no external close operation |

Cleanup failures are not allowed to overwrite the primary operation result unless a domain explicitly documents a stronger cleanup failure contract.

## Threading

- Immutable values are shareable after safe publication.
- Browsers, pages, frames, live elements, builders, and prepared operations are caller-confined unless explicitly documented otherwise.
- Wait, locator, action, extraction, workflow, and crawler operations are synchronous and create no hidden worker pool.
- `ObservationEngine` has no per-call mutable session state, but concurrent reuse is safe only if every injected collaborator is also concurrency-safe.
- Browser crawler is deliberately single-lane and uses one caller thread and one crawler-owned page.
- Plugin loading and callbacks are synchronous.

## Security and diagnostic text

- Only an explicitly documented safe/structural rendering is safe to log without inspecting its contents.
- Secret values and sensitive observation values are redacted or structurally excluded from framework-owned safe renderings.
- Typed accessors may intentionally return raw values, URLs, metadata, or causes; caller-owned redaction is required before logging/persistence.
- Recording excludes raw workflow inputs/outputs/action values and raw `Throwable`, but caller-controlled IDs/metadata are persisted verbatim and must be non-sensitive.
- Plugins are trusted in-process Java code, not sandboxed.
- No general SSRF firewall is implied by HTTP(S) scheme validation or crawler host scoping.

See [Security model](security-model.md).

## Identity and ambiguity

- Backend candidate identity is a structural identity input to deduplication/stability, never user-visible text, mutable attributes, DOM index, or application-controlled globals.
- Structured semantic scopes are hard constraints. If the scope is missing, resolution is not allowed to escape to an unrelated region; if it is ambiguous, ambiguity fails closed before target selection.
- Reordering a still-live physical structured scope must not silently retarget it. Physical replacement is distinguished from same-node reordering for already-bound live scopes, while a fresh semantic reference may resolve a valid replacement on a later independent resolution.
- Late semantic duplicates must surface as ambiguity before any physical identity guard can collapse them to one target.

## Intentional domain differences

- HTTP and browser crawlers have separate requests/results/failures because HTTP response semantics do not map honestly to browser navigation/observation.
- HTTP crawling continues after per-page failure by default; workflows fail fast because steps form an ordered side-effect/dataflow sequence.
- Browser crawler has a cooperative cancellation token; action cancellation is interruption-based.
- Wait timeout may retain a last informational value; locators never fabricate an element.
- Recording replay comparison ignores diagnostic prose/type-name drift that is intentionally non-semantic, while one recording must still be internally consistent.
