# ADR 0005: Keep the core unaware of AI systems

- Status: Accepted
- Date: 2026-08-13

## Context

Future optional agents or MCP tools may benefit from observations and verified actions, while the base
library must remain deterministic and useful without them.

## Decision

Do not add AI, LLM, MCP, prompt, token, chat, or model dependencies and concepts to core or domain
modules. Future integrations must call the same public observation, locator, action, workflow, and
verification API as every other consumer.

## Consequences

The core stays small, testable, and vendor-neutral. Optional decision systems cannot bypass action
verification or reach native browser objects.
