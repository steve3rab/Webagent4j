# ADR 0001: Use Java 21 as the minimum

- Status: Accepted
- Date: 2026-08-13

## Context

The project needs a current LTS runtime and simple concurrency for future blocking HTTP and storage
workloads.

## Decision

Compile to Java 21-compatible bytecode and support builds and runtime use on Java 21 or later.
Configure Maven Compiler Plugin with `--release 21` and Maven Enforcer with the open-ended `[21,)`
JDK range. Use records, switch expressions, immutable collections, and virtual threads only where
they clarify real code. Avoid a reactive framework by default.

## Consequences

Consumers need Java 21 or later. Java 21 remains the minimum CI baseline, while the Enforcer plugin
accepts later JDK feature releases and rejects only versions below 21.
