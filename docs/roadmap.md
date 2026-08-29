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

## 1.1: Governed execution

`1.1.0-SNAPSHOT` (`develop`) adds opt-in governed execution: `IActionPolicy` authorizes an action
before its backend side effect runs, and `INetworkPolicy` authorizes a `NAVIGATE` action's or a
crawler's network destination before a request is sent, both built on a shared synchronous
`IExecutionPolicy` contract with composition (`ExecutionPolicies.allOf`) and decision provenance
(`ActionResult#decisionTrace()`). Default behavior is unchanged; nothing is governed unless a caller
configures a policy. See [Governed execution](governed-execution.md).

This is not a general SSRF firewall, a policy sandbox, or a remote/LLM-assisted authorization layer.
It is also not a blanket DNS-rebinding defense: `HttpCrawler` closes the check-to-connect window only
for the specific case where the configured network policy exposes `INetworkAddressAuthority` (the
built-in declarative policy does; a fully custom policy does not unless it implements that capability
too), and browser navigation has no equivalent transport-level seam at all - see that document's
"What this is not" section for the precise scope.

## Post-1.0 candidates

Governed execution above addresses the "stronger network policy" item below for the destinations
this framework itself requests; full SSRF isolation, `robots.txt` support, adversarial robustness
qualification for Firefox and WebKit on operating systems beyond Linux, distributed crawling,
additional observation/extraction capabilities, explicit persistence, and optional external
decision-system/MCP adapters remain candidates.

These are candidates only. None is implied by the 1.0 API contract, and any optional decision/AI layer must consume the same fail-closed public contracts rather than bypassing them.
