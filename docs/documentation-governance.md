# Documentation governance

This document defines how WebAgent4J documentation is maintained so one behavioral fact has one authoritative home.

## Documentation classes

| Class | Purpose | May define current contract? |
| --- | --- | --- |
| Generated Javadoc | Exact type/method/parameter/exception semantics | Yes, for the API element it documents |
| `api-stability.md` | Supported Java/Maven surfaces and SemVer policy | Yes |
| `contracts.md` | Framework-wide invariants and intentional cross-domain differences | Yes |
| Domain guides | Domain-specific behavior, examples, ownership, failure semantics | Yes |
| `security-model.md` | Trust boundaries and security responsibilities | Yes |
| `limitations.md` | Explicit exclusions and support gaps | Yes |
| `support-matrix.md` | What is implemented, release-gated, or best-effort | Yes |
| `testing.md`, `robustness.md`, `hardening.md` | Evidence and verification method | No; they prove contracts rather than redefine them |
| ADRs | Why an architectural choice was made | No; current guides win if the implementation later evolves |
| `migration-to-1.0.md` | Pre-1.0 migration history | No |
| `roadmap.md` | Possible future work | No |

## Rules

- Do not duplicate a full status matrix, failure matrix, timeout rule, or security rule in several guides. State it once in its authoritative document and link to it elsewhere.
- Do not use development-phase labels such as “Phase 0.8” as the identity of a current user guide. Development history belongs in the roadmap, migration notes, ADRs, and hardening evidence.
- Do not place exact test totals, coverage percentages, transient CI incidents, pull-request SHA values, or intermediate implementation designs in normative user documentation.
- Keep examples compile-oriented and use current public APIs. Avoid snapshot-version literals when a version-neutral example is possible.
- A supported behavior change must update Javadoc, the relevant domain guide, `contracts.md` when cross-module, `limitations.md` when a limitation changes, and migration/release notes when compatibility is affected.
- A security-boundary change must update `security-model.md` and any affected safe-rendering or caller-responsibility statement in the relevant domain guide.
- A release must publish immutable versioned Javadoc/documentation in addition to a moving `latest` view.

## Review checklist for documentation changes

Before merge, verify that:

- links and anchors resolve;
- examples use supported APIs and current module names;
- no statement depends on an old development phase being current;
- “safe”, “secure”, “supported”, “thread-safe”, “exactly once”, and “timeout” claims match the precise contract rather than a stronger informal interpretation;
- secrets, raw exception causes, URLs, recording metadata, and plugin metadata are described with their correct trust boundary;
- limitations are not phrased as future promises unless the roadmap explicitly owns that promise;
- any new public artifact or SPI is classified in `api-stability.md` and `modules.md`.
