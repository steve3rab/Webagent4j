# Roadmap

The roadmap is non-normative. It describes direction, not a compatibility promise or delivery date. Current behavior is defined by Javadoc and the guides linked from [index.md](index.md).

## Completed foundations

The pre-1.0 program established:

- browser lifecycle and Playwright adapter;
- deterministic semantic locators/scopes/frames;
- bounded semantic observation;
- verified actions and shared verification/wait primitives;
- deterministic extraction;
- sequential HTTP and browser crawlers;
- sequential fail-fast workflows with typed variables/secrets;
- workflow Recording JSON V1 and offline comparison;
- explicit trusted custom locator plugins;
- public API stabilization/cross-module invariant alignment;
- adversarial numeric/timing/backend/identity/resource hardening.

Historical phase labels are retained only as release-development history; user guides no longer require knowledge of those phases.

## 1.0 release engineering

`1.0.0` is published. Its release engineering covered release engineering/documentation/publication rather than a new product feature:

- final API/SPI/artifact/CLI support review;
- current security policy alignment;
- immutable versioned Javadoc/documentation;
- shaded-artifact license/notice review;
- exact-head CI/CodeQL/Dependency Review/robustness certification;
- migration/release notes and post-publication smoke verification.

Maven/publication metadata/signing/repository configuration for public Maven distribution (for example, Maven Central) remains a separate, not-yet-selected item.

See [release.md](release.md) for the runbook used for this and future releases.

## Post-1.0 candidates

Potential later work includes stronger network policy/SSRF tooling, `robots.txt` support, broader browser robustness tiers, distributed crawling, additional observation/extraction capabilities, explicit persistence, and optional external decision-system/MCP adapters.

These are candidates only. None is implied by the 1.0 API contract, and any optional decision/AI layer must consume the same fail-closed public contracts rather than bypassing them.
