# ADR 0003: Use Playwright as the first browser backend

- Status: Accepted
- Date: 2026-08-13

## Context

The first vertical needs a maintained Chromium automation engine with accessibility-aware locators.

## Decision

Implement Playwright Java behind `webagent4j-browser-api`. Discover the adapter through `ServiceLoader`.
Never expose Playwright classes in public WebAgent4J contracts.

## Consequences

Playwright and its browser binary are runtime costs only for users choosing that adapter. Selenium,
remote, or other adapters can be added without changing the core facade contract.
