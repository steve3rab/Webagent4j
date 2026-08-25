# ADR 0005: Keep the core deterministic and independent of AI dependencies

**Status:** Accepted
**Supersedes:** None

## Context

Semantic web automation must remain inspectable, deterministic, runnable locally, and usable without model/network services. Optional decision systems should not redefine the safety semantics of the browser core.

## Decision

Production core/domain modules do not depend on AI/LLM frameworks or expose model/prompt/token concepts as required core contracts. Future optional decision or MCP adapters must consume the same public observation/locator/action/workflow contracts as ordinary applications.

## Consequences

The deterministic engine can fail with explicit ambiguity/unresolvable outcomes instead of guessing. Optional higher-level decision systems can be added separately without making them a runtime dependency or bypassing action-safety invariants.
