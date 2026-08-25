# ADR 0008: Action and verification engine

**Status:** Accepted
**Supersedes:** None

## Context

Browser actions have preconditions, asynchronous settling, postconditions, target races, and potentially irreversible side effects. Retrying a complete operation can duplicate submissions, purchases, downloads, or other effects.

## Decision

Model actions as immutable prepared commands executed through one staged pipeline: semantic resolution, preconditions, backend invocation, stabilization, observation, verification, structured result. Backend invocation for one execution path occurs at most once; read-only resolution/verification may poll. `plan()` and dry-run share resolution/preconditions without invoking the backend, and plan execution revalidates from scratch.

## Consequences

Failures preserve whether backend execution was `REAL`, `DRY_RUN`, or `NOT_EXECUTED`. Callers can avoid unsafe blind retry after an uncertain real invocation. Verification remains read-only and backend-neutral.
