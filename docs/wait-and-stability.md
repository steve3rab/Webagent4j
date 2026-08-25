# Wait and stability

`webagent4j-wait` is the shared deterministic polling/deadline primitive used by locator resolution, verification, and action stabilization/postconditions. It performs no browser action itself.

## Core contracts

### `IMonotonicClock`

Provides monotonic nanosecond time for elapsed/deadline arithmetic. Production uses `System.nanoTime()` semantics. Wall-clock time is not used for timeout decisions.

### `IWaitSleeper`

Sleeps/parks the caller thread between polls. Interruption is preserved and surfaced as `WaitInterruptedException` rather than swallowed.

### `WaitBudget`

A started monotonic timeout allowance shared across sequential sub-operations. `remaining()` shrinks; handing the same budget onward does not reset the clock.

The implementation compares rollover-safe elapsed deltas instead of depending on a naive absolute signed `nanoTime()` deadline. Duration arithmetic saturates rather than overflowing into contradictory remaining/expired state.

A zero allowance is valid specifically so `WaitEngine.await` can still perform its documented immediate probe; domains that require positive configured timeouts validate them before creating budgets.

### `WaitPolicy`

Immutable polling interval plus optional `stableFor`. The timeout is not stored in the policy; it belongs to the budget.

### `IWaitProbe<T>` and `WaitSample<T>`

A probe is one read-only observation. It returns pending or satisfied. A pending sample may retain a last informational value. A satisfied sample may provide a stability key.

A thrown exception propagates. `WaitEngine` does not guess whether a domain exception means absence, ambiguity, or backend failure; the domain adapter decides which known conditions become pending.

### `WaitResult<T>`

Contains SUCCESS/TIMED_OUT, attempt count, elapsed duration, value, and achieved stability where applicable. Success requires a value; timeout never claims an achieved-stability duration.

## Poll algorithm

- probe immediately, even when the budget is already zero/expired at entry;
- if pending and time remains, sleep for `min(pollingInterval, remaining)`;
- never sleep past the deadline;
- never perform a hidden side effect;
- interruption stops the wait and preserves caller interrupt status.

## Stability

`stableFor` requires continuous satisfaction of the **same identity**, represented by an equality-comparable stability key. A pending sample or changed key resets the stability window. Non-contiguous satisfied periods are never accumulated.

For locators the key is based on deterministic candidate identities, not visible labels or names that different DOM nodes can share.

## Domain adapters

### Locator

One live context is resolved fresh per poll. Structured scopes therefore participate in the same wait instead of being resolved once before the wait. Context not-found may be pending; context ambiguity/backend failure propagates immediately.

### Verification

Verification is the probe. The shared-budget overload lets a caller enforce one deadline across several conditions.

### Action

Action target resolution, stabilization, and postconditions consume one action budget. The actual backend side effect remains outside probes and is checked against the budget before invocation.

## What a timeout does not mean

Timeout does not imply that a backend call already in progress was forcibly aborted at exactly the configured instant. The framework guarantees bounded scheduling/checking where the underlying API supports it and preserves `REAL` execution mode once invocation may have occurred.
