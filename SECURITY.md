# Security Policy

## Supported versions

Security fixes target the current development line and supported stable release lines.

Pre-release snapshots, release candidates, and older release lines may receive fixes only when the
maintainers explicitly designate them as supported. The release notes for a published version are the
authoritative source for its support status.

## Reporting a vulnerability

Do not open a public issue for a suspected vulnerability.

Use GitHub's
[private vulnerability reporting](https://github.com/steve3rab/Webagent4j/security/advisories/new)
and include, when possible:

- the affected version or commit;
- a minimal reproduction;
- the security impact;
- whether exploitation requires untrusted page content, a malicious plugin, caller-controlled input,
  or another precondition;
- any mitigation or fix you have already identified.

Do not include real credentials, access tokens, personal data, payment data, production cookies, or
other secrets in a report.

Maintainers aim to acknowledge complete reports within seven days. Validation, remediation, release,
and coordinated-disclosure timing depend on severity, reproducibility, affected versions, and the
availability of a safe fix.

## Security model

WebAgent4J is a deterministic web-automation library. It is not a browser sandbox, network security
gateway, anti-bot bypass, credential vault, or isolation boundary for untrusted Java code.

The detailed security and trust-boundary model is maintained in
[`docs/security-model.md`](docs/security-model.md). The summary below defines the most important
expectations for security reports and deployments.

### Network targets and SSRF

WebAgent4J accepts caller-authorized HTTP(S) targets, including local or private-network targets when
the caller supplies or permits them.

The framework does **not** provide a universal SSRF defense. HTTP and browser crawlers enforce their
documented scheme, host, domain, redirect, response-size, and crawl-scope rules, but those controls do
not replace an application-level destination policy.

Applications that accept URLs or crawl configuration from untrusted users must apply their own
allowlist or equivalent network policy before invoking WebAgent4J.

`robots.txt` is not automatically enforced. Applications remain responsible for legal, contractual,
operational, and site-policy compliance.

### Browser and page trust boundary

Browser pages and their JavaScript are untrusted input.

WebAgent4J's semantic locator and action layers are designed to fail closed when target identity or
uniqueness cannot be established. Ambiguity must not be converted into a guessed target, and opaque
backend/runtime failures must not be fabricated into absence or success.

Backend-native Playwright objects are not part of the supported public API.

`IPage#evaluate(...)` deliberately executes caller-supplied JavaScript in the page context. Code
passed to that API is trusted application input and is outside WebAgent4J's redaction or safety
guarantees.

### Actions and retries

WebAgent4J does not hide retries of a browser side effect.

Read-only resolution, stabilization, and verification may poll according to their documented
policies, but a non-idempotent backend action is not automatically repeated by wait logic. If the
framework cannot prove a safe unique target, execution fails rather than guessing.

A backend operation that has already started may outlive a Java-side timeout; a timeout is not a
process-level kill guarantee.

### Secrets and diagnostics

Framework-owned safe renderings intentionally omit or redact sensitive values only where their
contract explicitly says so.

Do not assume that an arbitrary `toString()`, raw exception, URI, extracted value, plugin-provided
message, browser value, or application metadata field is safe to log or persist.

In particular:

- observation input values are opt-in and sensitive controls remain redacted;
- workflow secret masking is a rendering boundary, not encryption or a vault;
- raw `Throwable` values retained for in-process diagnostics must be treated as sensitive;
- `RecordingId`, `ActionId`, plugin identifiers, versions, and other documented caller metadata may
  be persisted verbatim and therefore must not contain secrets;
- recording JSON V1 excludes the documented raw workflow/action value channels, but it does not
  attempt to classify arbitrary caller-provided identifiers as secrets.

See [`docs/security-model.md`](docs/security-model.md) and
[`docs/recording.md`](docs/recording.md) for the complete boundaries.

### Plugins and extension points

Plugins are trusted Java code running in-process with ordinary JVM permissions.

`PluginLoader` validates registration shape, identity, ordering, and supported contribution types,
but it does not sandbox plugin constructors or callbacks. A malicious or defective plugin can block,
perform I/O, modify process-global state, consume resources, or otherwise behave like any other
application dependency.

Do not load untrusted plugin JARs.

### Resource and path safety

Callers own the outer resources they create unless a more specific contract explicitly transfers
ownership. Use try-with-resources for browsers and other closeable resources.

Upload and download paths, screenshot destinations, filesystem permissions, and application-level
retention policies remain caller responsibilities except for the explicit validation performed by
the relevant WebAgent4J API.

### Explicit non-goals

WebAgent4J does not attempt to:

- bypass authentication, CAPTCHA, consent, anti-bot controls, or access restrictions;
- disguise browser fingerprints or rotate proxies;
- sandbox malicious Java plugins;
- provide a credential manager, secret store, or encryption service;
- provide a complete SSRF firewall;
- guarantee that arbitrary third-party page content is safe to persist or log;
- make browser/network side effects transactional.

## Dependency vulnerabilities

Reports about vulnerable dependencies are welcome when they affect a reachable WebAgent4J execution
path or distributed artifact.

Automated dependency alerts are triaged separately from exploit reports. A dependency version alone
does not establish impact; include the affected path or advisory when available.

## Disclosure

After a vulnerability is fixed, maintainers may publish a GitHub Security Advisory and release notes
describing affected versions, impact, and remediation. Security-sensitive implementation details may
be withheld until users have had a reasonable opportunity to update.
