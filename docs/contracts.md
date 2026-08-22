# Cross-module contracts

This page is the Phase 1.0-B consistency matrix for WebAgent4J's supported API and SPI candidate.
It defines the rules shared across modules and records the places where two domains intentionally
use different contracts. Domain guides remain authoritative for details; this page prevents a local
term or convenience from being mistaken for a framework-wide abstraction.

Phase 1.0-B adds no product feature, generic result framework, extension point, or recording schema
version. It preserves the Phase 1.0-A public signature candidate while correcting invalid public
states and fail-closed backend classification.

## Common terminology

| Term | Cross-module meaning |
| --- | --- |
| Success | The operation's documented objective completed. It does not imply that every optional diagnostic exists. |
| Completed | A terminal result, not necessarily a success. `WorkflowStatus.COMPLETED` is narrower and means every workflow step succeeded or was skipped. |
| Expected absence | The requested entity is demonstrably not present. It may use an empty `Optional`, typed not-found result, or typed not-found exception according to the domain. |
| Ambiguous | More than one candidate satisfies a contract that requires one. Ambiguity is never downgraded to absence. |
| Backend failure | An unexpected failure of the browser, transport, provider, or other adapter. It is never fabricated into absence or success. |
| Timeout | A configured deadline was not satisfied. Timeout is distinct from interruption, cancellation, ambiguity, and generic backend failure. |
| Duration | Elapsed or configured time. Elapsed durations are non-negative; configured polling/action/request timeouts are positive unless a domain explicitly documents a zero-budget immediate probe. |

## Results and failures

| Domain | Success representation | Expected unsuccessful outcome | Unexpected failure boundary |
| --- | --- | --- | --- |
| Wait | `WaitResult` with `SUCCESS` and a value | `TIMED_OUT`; a last informational value may remain | Probe runtime failures propagate; interruption becomes `WaitInterruptedException` with interrupt status preserved |
| Locator | `LocatorResult` | Typed not-found or ambiguity result/exception according to the terminal operation | Backend/runtime failures propagate and are never converted to empty lookup results |
| Verification | `VerificationResult.success() == true` | Mismatch or timeout in a structured result | Verification callback failures follow the verification engine contract |
| Action | `ActionResult` with `ActionStatus.SUCCESS` and no failure | Structured non-success status plus one `ActionFailure` | Supported pipeline failures are classified; raw retained causes are sensitive in-process diagnostics |
| Extraction | `ExtractionResult` or typed extracted value | Structured validation/conversion failure | Unexpected implementation failures propagate at the documented boundary |
| HTTP crawler | `CrawlResult` containing successful pages | Per-URL `CrawlFailure`; by default sibling URLs continue | Opaque fetcher failures become `BACKEND_FAILURE`, never a fabricated response |
| Browser crawler | `BrowserCrawlResult` containing successful pages | Browser-specific `BrowserCrawlFailure`; cancellation is cooperative | Backend failures use the browser taxonomy and can trigger fail-fast termination |
| Workflow | `WorkflowResult` with `COMPLETED` | First runtime failure produces one `FAILED` step and later `NOT_RUN` steps; preflight failure produces all `NOT_RUN` | Supported condition/action runtime failures become safe structured failures; JVM errors are not caught |
| Recording/replay | Valid immutable recording and structured replay comparison | Malformed documents are rejected; mismatches are comparison data | Codec/runtime defects propagate through the documented recording exception boundary |
| Plugins | Complete immutable `PluginRegistry` | Loading is all-or-nothing through `PluginLoadException` and `PluginLoadFailure` | Provider failures are bounded and translated without exposing arbitrary provider messages |

The modules intentionally retain domain-specific result types. A universal `Result<T, E>` would
erase important differences such as a wait's last value, an action's execution mode, a crawler's
partial page set, and a workflow's full fail-fast trace.

The exact action status/execution-mode/failure-type matrix is enforced at every layer that carries
all three fields: `ActionResult`, a workflow `ACTION_FAILED` projection, and a recorded
`ACTION_FAILED` projection. Success is `REAL` or `DRY_RUN` without a failure; precondition failure
is `NOT_EXECUTED/PRECONDITION_FAILED`; resolution failure is
`EXECUTION_FAILED/NOT_EXECUTED` with `TARGET_NOT_FOUND`, `TARGET_AMBIGUOUS`, or
`BACKEND_FAILURE`; an attempted execution failure is `EXECUTION_FAILED/REAL` with
`TARGET_NOT_INTERACTABLE`, `ACTION_NOT_SUPPORTED_BY_TARGET`, `BACKEND_FAILURE`,
`UPLOAD_FAILURE`, or `DOWNLOAD_FAILURE`; verification failure is
`VERIFICATION_FAILED/REAL/POSTCONDITION_FAILED`; timeout is `NOT_EXECUTED` or `REAL` with
`TIMEOUT`; cancellation is `CANCELLED/NOT_EXECUTED/INTERRUPTED` before backend invocation or
`CANCELLED/REAL/INTERRUPTED` after invocation or once a side effect may have started. Cancellation
is never `DRY_RUN`, and the caller thread's interrupt flag remains set.

### Workflow and recording projection

Every public `WorkflowResult` accepted by its constructors is now representable by the recording
model:

- every result and recording contains at least one step;
- a completed result contains only `SUCCEEDED` and `SKIPPED` steps;
- a preflight failure has no step ID and every step is `NOT_RUN`;
- a runtime failure has exactly one matching `FAILED` step, only successful/skipped predecessors,
  and only `NOT_RUN` successors;
- a failed step publishes no output, and its own failure names that step;
- `ASSIGN` and `ACTION` steps carry only the output/action-summary shapes the engine can emit;
- only `ACTION_FAILED` carries `ActionFailureType`, and it obeys the exact action matrix above.

These are constructor invariants, not a second workflow execution algorithm. Recording schema V1
is unchanged.

## Timeout and duration matrix

| Domain | Configured timeout | Clock/deadline | Precision and zero behavior |
| --- | --- | --- | --- |
| Wait | `WaitBudget` accepts zero for one immediate probe | Injected monotonic clock | Poll interval and stability window are positive |
| Locator | Positive definition/config timeout | One shared `WaitBudget` per resolution | Always performs the documented immediate probe; browser adapter precision is whole milliseconds where Playwright requires it |
| Verification | Positive polling interval; timeout or shared budget supplied by caller | Shared monotonic budget when the budget overload is used | The fixed-duration `awaitAll` overload intentionally gives each verification its own timeout |
| Action | Positive overall action timeout | One monotonic clock for the shared budget, total/phase/event elapsed timing; wall clock only for absolute audit timestamps | An already-running backend side effect is not forcibly interrupted when the budget expires |
| HTTP crawler/fetch | Positive request/crawl timeouts | Monotonic elapsed measurement | `HttpFetchRequest` rejects zero; HTTP status responses are not timeout failures |
| Browser crawler | Positive whole-millisecond navigation timeout and stability window | One navigation/stability budget | Stability cannot exceed navigation timeout |
| Observation | Positive observation budget | Monotonic elapsed enforcement | Snapshot/statistics/event elapsed durations are non-negative |

All public elapsed timing values reject negative durations. This includes wait results, locator
diagnostics/events, verification results, action results/events/stabilization, observation
snapshots/statistics/events, and HTTP fetch results.

## Resource ownership

| Resource | Owner | Close behavior |
| --- | --- | --- |
| `IBrowser` / `IPage` | The caller that creates them | Caller closes them; closing a browser closes its pages and backend resources |
| Browser-crawler page | `BrowserCrawler` | Created lazily, reused synchronously, and closed in `finally` on success, failure, fail-fast, or cancellation |
| Browser passed to `BrowserCrawler` | Caller by default | Closed only when `closeBrowserOnCompletion(true)` explicitly transfers that responsibility |
| Workflow-captured page/action resource | Caller or application factory | Workflow never creates, closes, caches, or transfers it |
| Plugin `ClassLoader` | Caller | `PluginLoader` neither closes nor globally caches it |
| HTTP response body | `JavaHttpFetcher` during the call | Fully consumed into a bounded byte array; no stream ownership escapes in `HttpFetchResult` |
| Immutable result/snapshot/definition | No external resource owner | No close operation |

Cleanup is deterministic, but a best-effort cleanup failure is not promoted into a second result
taxonomy unless the domain guide explicitly says so. Applications remain responsible for closing
their own outer resources.

## Threading and cancellation

| Component | Threading contract | Cancellation/interruption |
| --- | --- | --- |
| Immutable values and definitions | Shareable after safe publication | Not applicable |
| Builders, prepared actions, pages, and browsers | Caller-confined unless stated otherwise | Action interruption becomes `CANCELLED` where the action pipeline handles it |
| `WaitEngine` | Synchronous on the caller thread | Preserves interruption status and throws `WaitInterruptedException` |
| Locator, action, extraction, workflow, and HTTP crawler engines | Synchronous; no hidden executor or worker pool | Domain-specific interruption behavior; no universal cancellation token |
| `ObservationEngine` | Retains no per-call mutable state; concurrent sharing requires every injected collaborator to be concurrency-safe | Checks caller-thread interruption |
| `BrowserCrawler` | One caller thread and one crawler-owned page | Explicit cooperative `CancellationToken` |
| Plugin loading/callbacks | Synchronous on the calling thread | No hidden cancellation or retry |

The browser crawler's token is intentionally not generalized to actions, waits, workflows, or HTTP
crawling. Those operations have different side-effect and partial-result semantics.

## Security and trust boundaries

| Data | Framework rendering | Explicit typed access |
| --- | --- | --- |
| Secrets and observed input values | Redacted or omitted | Available only through documented explicit APIs needed to perform the operation |
| Action event target/result/metadata text | Excluded from `ActionEvent#toString()` | Retained by accessors for in-process consumers that apply their own policy |
| Failure causes | Excluded from safe summary rendering | Some action/crawler failures retain the raw cause; treat it as sensitive |
| URLs and extracted raw values | Omitted from safe exception/failure text where documented | Typed URI/value accessors remain available in-process |
| Recording metadata | Stored verbatim by design | Caller must keep keys and values non-sensitive before recording |
| Plugin identifiers, versions, strategy IDs | Treated as trusted diagnostic metadata after validation | Provider/application owns their content |
| SPI callbacks | Trusted in-process code, not sandboxed | Callback failures follow the declaring SPI contract |

Only surfaces explicitly documented as safe or structural diagnostic renderings provide a
no-untrusted-text guarantee. General value/result `toString()` output must not be treated as a
logging or persistence boundary unless its domain contract explicitly documents that guarantee.
Native Java serialization is not a persistence contract; recording JSON V1 is the only stable
framework-owned persistence format.

## Identity, normalization, and ordering

- `ActionId`, `WorkflowId`, `WorkflowStepId`, `RecordingId`, and `PluginId` preserve their validated
  textual identity. `PluginId` additionally has a restricted lowercase syntax.
- `ObservationId` and `SemanticElementId` trim surrounding whitespace because they are capture-local
  semantic identities rather than caller-persisted workflow/recording keys.
- Locator candidates use deterministic score, evidence, and DOM-order tie-breaking.
- Workflows and recordings preserve definition order; HTTP and browser crawlers preserve their
  documented frontier/result order; plugins sort provider and strategy contributions explicitly.
- Unordered caller input is never promised a new semantic order merely because an implementation
  snapshots it with an immutable collection.

These normalization differences are intentional. No universal UUID format or global ID base class
is introduced.

## Intentional domain differences

| Difference | Reason |
| --- | --- |
| HTTP and browser crawlers have separate requests, results, and failure enums | HTTP status/redirect semantics do not map honestly to rendered-page navigation and observation failures |
| HTTP crawler continues after per-page failure by default; workflow always fails fast | Crawling produces a useful partial graph, while workflow steps form an ordered dataflow with side effects |
| Browser crawler has a cancellation token; action cancellation is interruption-based | A crawl has a natural cooperative frontier; an action may already have performed a non-repeatable side effect |
| Wait timeout may carry a last informational value; locator not-found does not fabricate an element | The wait primitive reports probe history, while a locator must never invent a target |
| Verification's fixed-duration list overload uses per-condition timeouts; action uses one shared budget | The former preserves its explicit standalone API contract; the action must bound the whole side-effecting pipeline |
| Recording compares structural failure fields but ignores diagnostic message/type-name drift across executions | Diagnostics may vary without changing replay semantics |
| `FRAME_ACCESS_FAILED` is reserved until browser-crawler frame traversal exists | Keeping the documented enum value avoids premature churn; it is explicitly unreachable today |

## Extension-point consistency

All supported SPIs require non-null inputs and outputs as documented, execute synchronously unless
their contract says otherwise, receive no sandbox, and transfer no resource ownership implicitly.
WebAgent4J does not retry arbitrary extension callbacks. Plugin discovery is explicit and
all-or-nothing; ordinary engine construction loads zero plugins.

See [API stability policy](api-stability.md) for the authoritative supported SPI inventory and
[Migration to the 1.0 API candidate](migration-to-1.0.md) for the pre-1.0 behavioral corrections.
