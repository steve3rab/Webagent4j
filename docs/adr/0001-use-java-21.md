# ADR 0001: Use Java 21

**Status:** Accepted
**Supersedes:** None

## Context

WebAgent4J needs a single modern Java baseline for records, sealed hierarchies, current language/library capabilities, reproducible CI, and a clear consumer compatibility floor.

## Decision

Java 21 is the minimum supported runtime and compiler bytecode target. Builds may run on later JDK feature releases, but compilation uses `--release 21`. Java 21 remains a required release-validation environment.

## Consequences

Consumers need Java 21 or later. The project can use Java 21 language/JDK capabilities without carrying legacy compatibility layers. Passing on a later JDK does not replace testing the Java 21 minimum.
