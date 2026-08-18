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
  `PENDING` (keep polling) or `SATISFIED` (with a value and, optionally, a `stabilityKey`). A
  probe that throws propagates the exception immediately - the engine has no way to know whether a
  given exception means "not there yet" or "ambiguous" or "backend crashed"; only the
  domain-specific probe can tell those apart, and it should turn only the ones it understands as
  retryable into `WaitSample.pending()`.
- **`WaitEngine`** - the orchestrator. `await(WaitBudget, WaitPolicy, IWaitProbe<T>)` always
  evaluates the probe immediately, before any sleep - an already-true condition never sleeps.
  Between polls it sleeps for `min(policy.pollingInterval(), budget.remaining())`, never past the
  deadline, and never issues a second sleep once the deadline has passed.
- **`WaitResult<T>` / `WaitStatus`** - `SUCCESS` or `TIMED_OUT`, attempt count, elapsed duration,
  the last sample's value, and - on success under a stability policy - how long it was stable.

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

`LocatorEngine`'s own polling loop still owns its candidate search, scoring, and ambiguity logic
unchanged - none of that moved. What changed is the timing underneath it: the loop's deadline and
remaining-budget arithmetic now go through `WaitBudget`, and `LocatorResolutionWaiter` - the
primitive that parks the thread between polls - now delegates to the shared `IWaitSleeper` instead
of calling `LockSupport.parkNanos` itself, so locator resolution sleeps through the exact same
primitive verification and action do.

### Verification (`webagent4j-verification`)

`VerificationPoller` is now a thin adapter over `WaitEngine`: `IVerification.verify(...)` is the
probe, a satisfied `VerificationResult` is `WaitSample.satisfied(...)`, and the poller no longer
owns a `Thread.sleep` loop or a wall-clock `Clock` at all - both are gone, replaced by the shared
monotonic engine.

`VerificationEngine.awaitAll(context, verifications, Duration timeout, Duration interval)` keeps
its original, still-documented contract: each condition gets its own independent timeout, so a
list of three conditions may together take up to `3 * timeout` in the worst case. A new overload,
`awaitAll(context, verifications, WaitBudget budget, Duration interval)`, shares one budget across
the whole list instead - the one `ActionExecutor` now uses for postconditions, so three
postconditions can no longer silently add up to three independent timeouts.

### Action (`webagent4j-action`)

`ActionExecutor` starts one `WaitBudget` from the action's configured timeout at the very start of
the pipeline and threads its `remaining()` through both stabilization and postcondition
verification (via the new shared-budget `VerificationEngine.awaitAll` overload). A postcondition
that consumes most of the budget correspondingly starves the next one, instead of each one
starting a fresh clock. `ActionTargetResolver`'s retry-before-execution loop no longer calls
`Thread.sleep` directly; it parks through the same `IWaitSleeper`.

The one non-negotiable invariant this migration deliberately preserves everywhere: the real
backend side effect (the click, the type, the submit) is never inside a wait loop's probe. A probe
may be evaluated many times; the action it revalidates is never re-executed because of it.

## What this migration does not change

Locator scoring, fuzzy matching, `tryFind()`, the typed locator-failure taxonomy, `dryRun()`
semantics, `ActionExecutionMode`, `IActionPlan`'s single-use guard and ID correlation, the
aggregate Playwright coverage gate, and the mixed-scope/explicit-scope-containment invariants from
earlier work are all unchanged. Structured locator scopes are still resolved once per terminal
operation, not re-resolved on every individual poll tick inside that operation's own stability or
timeout wait - closing that narrower gap is future work, not part of this migration.
