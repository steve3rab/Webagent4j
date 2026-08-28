# WebAgent4J documentation

WebAgent4J is a deterministic semantic web-automation foundation for Java 21 or later. The documentation is organized by authority rather than by development phase: current contracts live in the API, cross-module, security, and domain guides; historical rationale lives in ADRs, migration notes, the roadmap, and hardening evidence.

## Start here

1. [Getting started](getting-started.md) — build, consume, and run the first browser example.
2. [Public API](public-api.md) — which artifacts to depend on and the main entry points.
3. [Support matrix](support-matrix.md) — Java, operating-system, browser, and release-gating coverage.
4. [Known limitations](limitations.md) — what WebAgent4J intentionally does not guarantee.
5. [Security model](security-model.md) — trust boundaries, sensitive data, network policy, and plugin assumptions.

Generated method-level Javadoc is published separately at `https://steve3rab.github.io/Webagent4j/api/latest/`. For a tagged release, use the versioned Javadoc path published for that release; `api/latest` follows the current `main` branch and is not an immutable release reference.

## Guides

- [Browser lifecycle](browser.md)
- [Semantic locators and scopes](locators.md)
- [Semantic observation](observation.md)
- [Actions](actions.md)
- [Governed execution](governed-execution.md)
- [Verification](verification.md)
- [Wait and stability](wait-and-stability.md)
- [Extraction](extraction.md)
- [Crawler overview](crawler.md)
- [HTTP crawler](http-crawler.md)
- [Browser crawler](browser-crawler.md)
- [Workflows](workflow.md)
- [Recording](recording.md)
- [Plugins](plugins.md)
- [Command-line interface](cli.md)

## Reference and compatibility

- [Architecture](architecture.md)
- [Module graph](modules.md)
- [Cross-module contracts](contracts.md)
- [API stability policy](api-stability.md)
- [Recording schema V1](schema/recording-v1.schema.json)
- [Migration to 1.0](migration-to-1.0.md)

## Maintainer and release documentation

- [Documentation governance](documentation-governance.md)
- [Testing](testing.md)
- [Robustness benchmark](robustness.md)
- [Adversarial hardening evidence](hardening.md)
- [Release procedure](release.md)
- [Contribution guide](contribution.md)
- [Roadmap](roadmap.md)
- [Architecture decision records](adr/README.md)

## Which document wins if two statements conflict?

The order of authority is:

1. a supported public type or method's Javadoc for that exact API element;
2. [API stability](api-stability.md), [cross-module contracts](contracts.md), and [security model](security-model.md) for framework-wide commitments;
3. the relevant domain guide for domain-specific behavior;
4. [known limitations](limitations.md) for explicit exclusions and support gaps;
5. maintainer evidence (`testing.md`, `robustness.md`, `hardening.md`) for how contracts are verified;
6. ADRs, migration notes, changelog, and roadmap for historical rationale.

Historical documents do not override current contracts. Test counts, CI incident narratives, implementation experiments, and pull-request state are deliberately excluded from normative guides because they become stale quickly.
