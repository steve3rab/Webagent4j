# ADR 0001: Use Java 21

- Status: Accepted
- Date: 2026-08-13

## Context

The project needs a current LTS runtime and simple concurrency for future blocking HTTP and storage
workloads.

## Decision

Compile and run strictly on Java 21. Use records, switch expressions, immutable collections, and virtual
threads only where they clarify real code. Avoid a reactive framework by default.

## Consequences

Consumers need Java 21 or newer, and the Enforcer plugin rejects other build JDKs.
