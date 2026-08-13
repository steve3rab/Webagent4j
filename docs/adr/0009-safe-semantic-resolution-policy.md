# ADR 0009: Prefer safe semantic non-resolution

- Status: Accepted
- Date: 2026-08-13

## Context

Real pages contain duplicate labels, misleading text, inaccessible controls, transient DOM nodes, and
weak fuzzy similarities. Maximizing the number of resolved locators can select a semantically wrong
control and turn a recoverable locator failure into an incorrect side effect.

## Decision

WebAgent4J prefers an explicit absence of decision to an uncertain action. Locator outcomes are
formalized as `RESOLVED`, `AMBIGUOUS`, `UNRESOLVABLE`, `NOT_INTERACTABLE`, and `TIMEOUT`. Equivalent
top candidates remain ambiguous; insufficient evidence remains unresolvable; matching controls that
violate requested interaction state remain not interactable.

Fuzzy matching stays an exact-first, bounded, conservative fallback. It evaluates complete phrases,
rejects dangerous negated lookalikes, and cannot override an explicit accessible name with
contradictory visible text. The local deterministic benchmark treats every wrong target as a critical
failure and requires a count of zero. Server-side tracking verifies action targets and non-idempotent
execution counts independently from DOM success messages.

The core does not guess with AI. A future optional strategy may begin only after an explicit uncertain
outcome and must disclose strategy, confidence, reason, and policy before any action decision.

## Consequences

- Some pages that a human can interpret visually remain ambiguous or unresolvable.
- Callers receive structured, diagnosable failures instead of arbitrary DOM-order selection.
- Accessibility improvements and explicit scopes improve both user experience and automation safety.
- Resolution-rate changes cannot be evaluated without the wrong-target and safe-failure metrics.
- Benchmark expectation changes remain explicit in a version-controlled baseline.
