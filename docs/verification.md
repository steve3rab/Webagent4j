# Verification

`IVerification` is a deterministic, read-only condition evaluated against an `IVerificationContext`. `VerificationEngine` polls it with a bounded interval/deadline and returns structured `VerificationResult` values.

## Built-in families

- URL equality/contains/regex
- page title/text
- element existence/absence/visibility/enabled/editable/checked/selected/focused
- element text/attribute/value/count
- semantic observation-diff conditions
- composition with `allOf`, `anyOf`, and `not`

Element conditions use semantic references, so repeated evaluations observe current DOM state rather than a stale native handle.

## Polling

The first evaluation is immediate. A pending mismatch is evaluated again until success or timeout. Polling a verification never repeats the action that preceded it.

When invoked through the action pipeline, all postconditions share the action's remaining `WaitBudget`; several conditions cannot each silently receive the full original action timeout. The standalone fixed-duration `awaitAll` overload intentionally keeps its own per-condition timeout contract. Use the shared-budget form when a single overall deadline is required.

## Custom conditions

Custom `IVerification` implementations should be deterministic, read-only, fast, and safe to repeat. Returned expected/actual/description values may become diagnostics; do not embed credentials, authorization headers, tokens, payment data, or unrestricted page content.

Verification definitions/results are immutable. Thread safety of a custom verification depends on the custom implementation; the live page context remains caller-confined.
