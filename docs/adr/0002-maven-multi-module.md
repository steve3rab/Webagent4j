# ADR 0002: Use a Maven multi-module build

- Status: Accepted
- Date: 2026-08-13

## Context

Browser adapters, domain contracts, tools, and future capabilities need enforceable dependency
boundaries and independent consumer artifacts.

## Decision

Use one Maven reactor with a parent for versions and quality rules plus a consumer BOM. Keep module
edges acyclic and verify architecture rules in tests.

## Consequences

The reactor has more POM files, but concrete backend dependencies remain optional and domain boundaries
are visible to contributors and consumers.
