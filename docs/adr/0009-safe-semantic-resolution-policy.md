# ADR 0009: Safe semantic resolution policy

**Status:** Accepted, strengthened by 1.0 adversarial hardening
**Supersedes:** None

## Context

A deterministic engine can often resolve a semantically strong target, but some pages are ambiguous, inaccessible, dynamically replaced, or visually meaningful without machine-readable distinctions. Picking something merely to increase a success metric is unsafe.

## Decision

Prefer safe failure over unjustified selection. Ambiguity is explicit and does not become transient pending state for uniqueness-requiring operations. Backend/runtime failure does not become absence. Structured scopes are hard constraints. Dynamic identity must be backend-controlled and late semantic duplicates must remain ambiguous.

## Consequences

The framework may return not-found/unresolvable/ambiguous instead of acting. Visual-only/AI fallback is not part of the deterministic contract. Optional future decision systems must preserve uncertainty rather than silently converting it into an action.
