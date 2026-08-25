# ADR 0007: Semantic observation engine

**Status:** Accepted
**Supersedes:** None

## Context

Automation and diagnostics need a compact view of meaningful page structure without copying unrestricted DOM/HTML/input values or retaining live browser handles.

## Decision

Browser adapters provide one bounded backend-neutral snapshot. `ObservationEngine` transforms it into detached immutable semantic values with explicit truncation, redaction, deterministic rendering/fingerprint/diff, and portable locator intent.

## Consequences

Observation can be consumed without querying the live page again. Raw DOM/scripts/styles/storage are outside the model. Input-value handling is conservative and secret-sensitive fields remain redacted. Observation is bounded semantic state, not an atomic browser transaction or persistence format.
