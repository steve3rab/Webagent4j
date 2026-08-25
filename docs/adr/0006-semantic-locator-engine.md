# ADR 0006: Semantic locator engine

**Status:** Accepted, amended by later 1.0 hardening
**Supersedes:** None

## Context

Raw CSS/XPath selectors are often brittle and do not capture user-facing intent. A safe automation engine must combine semantic evidence, state constraints, deterministic ordering, ambiguity detection, dynamic re-resolution, and backend-neutral diagnostics.

## Decision

Provide a backend-neutral locator engine that plans deterministic strategy execution, merges evidence by backend candidate identity, applies hard constraints, ranks accepted candidates, and fails closed when uniqueness is not justified. Fuzzy matching is an explicit fallback policy. CSS/XPath remain explicit escape hatches.

Structured scopes are hard nested constraints and re-resolve against live state. Later hardening clarified that physical identity across Playwright scope races must not depend on DOM index, mutable page attributes, or page-controlled globals; semantic cardinality is checked before physical guards.

## Consequences

Applications get explainable `first`/`single`/`all`/reference behavior. Wrong-target avoidance takes precedence over maximizing resolution rate. Backends must provide stable candidate identity/capability data without leaking native types into the public API.
