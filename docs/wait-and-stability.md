# Wait and stability

`webagent4j-wait` is the one deterministic polling primitive shared by locator resolution,
verification polling, and action stabilization/postconditions. Before it existed, each of those
three domains implemented its own version of the same handful of concerns - a deadline, a polling
loop, a sleep between attempts, interruption handling - with subtly different bugs: one used
wall-clock time instead of a monotonic clock, one gave every condition in a list its own
independent full timeout instead of sharing a budget, and a fourth undocumented retry loop
(`ActionTargetResolver`) called `Thread.sleep` directly. `webagent4j-wait` is **one wait
primitive, several domain adapters, no parallel polling engines**.

## What it owns, and what it does not

The engine owns exactly four things: when to poll, when to stop, how much time remains, and when
a satisfied result has been stable long enough. It owns nothing about *what* is being waited for -
that is entirely the caller's `IWaitProbe`. It never performs a side effect itself. A probe is a
read; the one real side effect an action performs (a click, a form submission) always happens
once, outside any wait loop, never inside a probe.

## Core types (`io.webagent4j.wait`)

- **`IMonotonicClock`** - a source of monotonic time (`System.nanoTime()` in production). A
  timeout is never measured against `Instant.now()` or `System.currentTimeMillis()`, both of which
  can jump when the system clock is corrected.
- **`IWaitSleeper`** - parks the current thread between polls (`LockSupport.parkNanos` in
  production), preserving thread interruption status and throwing `WaitInterruptedException`
  instead of silently swallowing it.
- **`WaitBudget`** - a monotonic deadline, startable once and shared across several sequential
  sub-operations so a later one sees a shrinking `remaining()` instead of a fresh, independent
  timeout. Arithmetic is saturated, never allowed to overflow.
- **`WaitPolicy`** - immutable polling cadence: `pollingInterval`, and an optional `stableFor`
  duration. The timeout itself is not part of the policy - it belongs to the budget, so a policy
  can be reused across calls that share one deadline.
- **`IWaitProbe<T>` / `WaitSample<T>`** - a single, side-effect-free reading. A sample is
  `PENDING` (keep polling, optionally with an informational last-known value via
  `WaitSample.pending(T)`, preserved in `WaitResult.value()` on a timeout instead of discarded) or
  `SATISFIED` (with a value and, optionally, a `stabilityKey`). A probe that throws propagates the
  exception immediately - the engine has no way to know whether a given exception means "not there
  yet" or "ambiguous" or "backend crashed"; only the domain-specific probe can tell those apart,
  and it should turn only the ones it understands as retryable into `WaitSample.pending()`.
- **`WaitEngine`** - the orchestrator. `await(WaitBudget, WaitPolicy, IWaitProbe<T>)` always
  evaluates the probe immediately, before any sleep - an already-true condition never sleeps, and
  even a budget that is already expired when `await` is called still gets exactly one immediate
  probe rather than being skipped outright (this is what lets a shared budget be handed to a later
  condition in a list without any "at least 1ns" workaround). Between polls it sleeps for
  `min(policy.pollingInterval(), budget.remaining())`, never past the deadline, and never issues a
  second sleep once the deadline has passed.
- **`WaitResult<T>` / `WaitStatus`** - `SUCCESS` or `TIMED_OUT`, attempt count, elapsed duration,
  the last sample's value, and - on success under a stability policy - how long it was stable.
  Construction enforces the engine-reachable shape: success always has a value, timeout never has
  an achieved-stability duration, and elapsed/achieved-stability durations are non-negative. A
  timeout may still carry the final informational value supplied by `WaitSample.pending(T)`.

## Stability semantics

A `stableFor` window requires the *same* thing to remain satisfied continuously, not merely "some
satisfied thing" cumulatively. The engine tracks this with a caller-supplied `stabilityKey`: two
satisfied samples are "the same thing still satisfied" only when their keys are `equals()`. A
different key - or a `PENDING` sample in between - resets the window to zero, even if the total
time spent satisfied would otherwise have been enough. A stability key must be a real, stable
identity (a candidate's deterministic identity, an element handle), never free text such as an
accessible name or visible label, which can coincidentally repeat for two different underlying
things.

Using the exact same satisfied value again is treated as the identity remaining stable
(`stableKey.equals(key)` for an unchanged key) - a deliberate, documented choice: re-observing the
same thing is harmless, and requiring artificial "freshness" would only make stability windows
flakier for no safety benefit.

## Domain adapters

### Locator (`webagent4j-locator`)

`LocatorEngine` no longer owns a competing temporal coordinator. Its own `do`/`while` deadline,
stability-timer, and sleep loop is gone; `WaitEngine.await(WaitBudget, WaitPolicy, IWaitProbe)` is
now the one loop driving resolution, started against `waitEngine.clock()` - the same clock the
engine polls and sleeps with, so an injected fake clock (used by every deterministic wait test)
actually governs the budget's expiry too, instead of a separate `IMonotonicClock.systemClock()`
silently timing the deadline against real wall-clock time while the rest of the wait ran on fake
time. `LocatorEngine` keeps exactly the domain decisions: `searchOnce` is a single, immediate,
non-looping DOM search that becomes the probe (`probeOnce`), candidate scoring and filtering are
unchanged, and a `WaitSample.satisfied(candidates, stabilityKey)`'s `stabilityKey` is a structured,
order-preserving `List<String>` of each candidate's deterministic identity - never accessible name,
role, or any other diagnostic label. `LocatorResolutionWaiter` - the old primitive that parked the
thread between polls - had no remaining callers once the loop moved into `WaitEngine` and was
deleted outright rather than kept as an unused compatibility wrapper.

For `locateSingle()` specifically, every individual poll's candidate list is checked for ambiguity,
not only the final one: **ambiguity is a fail-safe condition, never a transiently-pending state**.
The moment a poll observes two candidates that satisfy the ambiguity condition, the probe throws
`AmbiguousLocatorException` immediately - the wait never continues hoping the DOM becomes less
ambiguous on its own, and it never silently narrows to whichever candidate happened to rank first.
A genuine backend/runtime failure during any poll propagates the same way, unchanged and
un-reinterpreted; `locate()`/`locateAll()`, which do not require uniqueness, do not perform this
check, matching their pre-existing, unchanged selection semantics.

`ILocatorEngine.locate()`/`locateSingle()`/`locateAll()` now come in two forms: a `LocatorContext`
overload (a default method, for a caller that already has one fixed context to search) and an
`ILiveLocatorContext` overload (`baseline()` for the stable, DOM-independent backend/config a wait
needs up front, `resolve()` called fresh on every polling attempt). `LocatorEngine`'s probe calls
`resolve()` once per attempt, not once for the whole wait, so a structured semantic scope this
context depends on - see `PlaywrightScopeResolver` in `webagent4j-browser-playwright` - is
re-evaluated against the current DOM throughout the wait, not only when it begins. A typed
"not found" failure resolving the live context (the scope does not currently exist) is treated
exactly like an absent target - `WaitSample.pending()`, retried on the next poll; an ambiguous or
backend/runtime failure resolving it propagates immediately, unconditionally, regardless of
`locate()` vs. `locateSingle()`: **context ambiguity is always a fail-safe condition**, not
something only `locateSingle()`'s target-ambiguity check cares about. Each structured-scope
container lookup this triggers is itself bounded to one immediate, non-waiting probe (a 1ns
timeout, relying on the engine's own "always at least one immediate probe, even against an
already-expired budget" guarantee) precisely so that resolving the scope chain inside one outer
poll attempt never starts a second, nested full-timeout wait - the outer `WaitBudget` remains the
only deadline governing the whole logical wait, however many scopes the chain has to re-resolve on
each attempt.

### Verification (`webagent4j-verification`)

`VerificationPoller` is a thin adapter over `WaitEngine`: `IVerification.verify(...)` is the
probe, a satisfied `VerificationResult` is `WaitSample.satisfied(...)`, and the poller owns no
`Thread.sleep` loop or wall-clock `Clock` at all - both are gone, replaced by the shared monotonic
engine. `await(verification, context, WaitBudget, Duration)` passes an existing budget straight
into `WaitEngine.await(...)` with no intermediate conversion to a remaining `Duration` and back
into a new budget; the `Duration`-timeout overload is a thin wrapper that starts one fresh budget
for that single call.

`VerificationEngine.awaitAll(context, verifications, Duration timeout, Duration interval)` keeps
its original, still-documented contract: each condition gets its own independent timeout, so a
list of three conditions may together take up to `3 * timeout` in the worst case. The
`awaitAll(context, verifications, WaitBudget budget, Duration interval)` overload passes the
*exact same* `WaitBudget` instance to every condition in the list instead - the one `ActionExecutor`
uses for postconditions, so three postconditions can no longer silently add up to three
independent timeouts.

### Action (`webagent4j-action`)

`ActionExecutor` starts one `WaitBudget` from the action's configured timeout at the very start of
the pipeline and threads that same instance through target resolution, stabilization, and
postcondition verification (via the shared-budget `VerificationEngine.awaitAll` overload). A
postcondition that consumes most of the budget correspondingly starves the next one, instead of
each one starting a fresh clock, and **the budget is checked again, explicitly, immediately before
the backend action runs**: if resolution and preconditions alone already exhausted it, the backend
is never invoked at all, and the action fails with the same `TIMEOUT` classification a postcondition
timeout uses.

`ActionTargetResolver`'s pre-execution retry loop consumes that same budget - a delay before the
next attempt is capped at `min(configuredDelay, budget.remaining())`, and no further attempt is
made once the budget is already expired - and only retries a demonstrated, typed **`NOT_FOUND`**
outcome (a resolved-but-detached element counts as `NOT_FOUND` too). `AmbiguousLocatorException`
and any other failure - a genuine backend/runtime error in particular - end resolution on the very
first attempt: they are rethrown immediately, never retried, exactly like a probe throwing out of
`WaitEngine` itself. It no longer calls `Thread.sleep` directly; it parks through the same
`IWaitSleeper`.

`IPreparedAction.plan()` never stores a live `WaitBudget`: its own target-resolution snapshot uses
a budget scoped to the `plan()` call alone, discarded once `prepare()` returns. `IActionPlan
.execute()` starts an entirely fresh budget when the real pipeline begins, so a plan built minutes
earlier never appears pre-expired at execution time.

The one non-negotiable invariant this migration preserves everywhere: the real backend side effect
(the click, the type, the submit) is never inside a wait loop's probe, and is invoked at most once.
A probe may be evaluated many times; the action it revalidates is never re-executed because of it.
Put precisely: **WebAgent4J will not start a backend side effect after its action budget has
expired, and it will never retry the backend side effect as part of wait/poll logic** - this is
deliberately not the same claim as "every action finishes before its timeout", which would be
false: a backend call already in flight when the deadline passes can still take longer to return.

## What this migration does not change

Locator scoring, fuzzy matching, `tryFind()`, the typed locator-failure taxonomy, `dryRun()`
semantics, `ActionExecutionMode`, `IActionPlan`'s single-use guard and ID correlation, the
aggregate Playwright coverage gate, and the mixed-scope/explicit-scope-containment invariants from
earlier work are all unchanged.

A structured locator scope is now re-resolved fresh on every individual polling attempt of the
target's own stability or timeout wait, not once per terminal operation as before - see
`ILiveLocatorContext` above. What remains unchanged, and is not claimed here, is anything about the
*ranking* or *matching* logic used to resolve that scope: the same accessible-name-then-visible-text
fallback, the same `containingText(...)` ordering and hard-constraint semantics, and the same
containment proof for an explicit element scope, all execute identically whether triggered once or
many times over the course of a wait.
