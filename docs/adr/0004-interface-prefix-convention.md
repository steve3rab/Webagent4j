# ADR 0004: Prefix interfaces with `I` and abstract classes with `A`

**Status:** Accepted
**Supersedes:** None

## Context

The project uses many explicit ports, policies, and extension interfaces. A consistent naming convention makes abstraction roles immediately visible across modules.

## Decision

Public/project interfaces use an `I` prefix and abstract base classes use an `A` prefix. New code follows the convention unless a language/runtime-required type name makes that impossible.

## Consequences

Naming is intentionally project-specific. Contributions and public APIs remain visually consistent, and refactors should not introduce mixed conventions.
