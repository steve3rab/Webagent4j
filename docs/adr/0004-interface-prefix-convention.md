# ADR 0004: Prefix interfaces with I

- Status: Accepted
- Date: 2026-08-13

## Context

The project specification chooses an explicit naming convention for interfaces and abstract classes.

## Decision

Prefix every interface with `I` and every abstract class with `A`. Unit tests end with `Test` and
integration tests with `IT`. Enforce the interface rule with ArchUnit and the test suffixes through
Surefire and Failsafe inclusion patterns.

## Consequences

The convention differs from common Java library style but makes architectural contracts visually
explicit and consistent throughout this project.
