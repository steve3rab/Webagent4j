# Security model

WebAgent4J is designed to fail closed when it cannot justify a unique action target, and to minimize accidental leakage from framework-owned diagnostics. It is **not** a browser sandbox, network firewall, credential vault, anti-bot bypass, or security scanner.

This document is the authoritative framework-wide security/trust-boundary reference. Domain guides may impose stricter rules.

## Trust model

WebAgent4J executes inside the caller's JVM and browser session. The following are considered caller-controlled or external/untrusted unless the application establishes a stronger trust relationship:

- URLs and remote page content;
- DOM/ARIA/text/attributes exposed by a page;
- data passed through public metadata IDs/labels;
- raw extraction results and raw exception causes;
- plugin JARs and custom SPI implementations;
- JavaScript passed to `evaluate`;
- files selected for upload/download destinations;
- network responses and redirects.

The framework's job is to preserve typed contracts and avoid turning uncertainty into a wrong target or fabricated success. It cannot make hostile code running in the same JVM harmless.

## Network and SSRF boundary

HTTP(S) scheme validation and crawler host/domain scoping are not a general SSRF defense.

- Browser navigation can intentionally target localhost, loopback, RFC1918/private, link-local, or internal HTTP(S) services if the caller supplies such a URL.
- The HTTP crawler constrains schemes and can restrict hosts/subdomains, but it has no universal private-address/IP-rebinding/network-zone firewall.
- The browser crawler inherits the caller's browser/session/network reachability and the same URL-authorization responsibility.
- Redirect validation follows each domain's documented scope rules, but applications that accept attacker-controlled destinations still need an application/network allowlist appropriate to their environment.
- `robots.txt` is not enforced by either crawler.

Do not expose a generic “crawl any URL” endpoint to untrusted users without a separate network policy.

### Optional governed execution

An `IActionPolicy` (action authorization) and an `INetworkPolicy` (network-destination
authorization) can be attached to a prepared action, `HttpCrawler`, or `BrowserCrawler` - neither is
configured by default, and omitting both leaves 1.0 behavior unchanged. `HttpCrawler` genuinely
prevents a denied request from ever being sent, for every hop it controls. A governed `NAVIGATE`
action or `BrowserCrawler` visit can only detect - never prevent - a browser-internal redirect that
lands somewhere denied, since browser redirect handling cannot be intercepted mid-flight; that case
is reported honestly as a post-execution violation on an action that already ran, never as a
prevented request. `HttpCrawler` additionally binds its actual transport connection to the exact
addresses a policy verified - closing the DNS-rebinding gap between check and connect - whenever
the configured policy implements `INetworkAddressAuthority` (the built-in `NetworkPolicies` policy
does); a fully custom `INetworkPolicy` gets no pinning unless it implements that capability too,
and browser navigation has no equivalent transport-level seam at all. None of this constitutes a
general SSRF firewall. A configured
policy is untrusted, unsandboxed Java code with the same trust posture as a plugin (see
[Plugins and custom SPIs](#plugins-and-custom-spis) below) - WebAgent4J guarantees it will not
invoke a governed backend before a policy allows it, and nothing more. See
[Governed execution](governed-execution.md) for the full contract.

## Semantic target safety

A wrong target is treated as more severe than a safe failure.

- `single()` and action target resolution fail on semantic ambiguity rather than silently using DOM order.
- Structured scopes are hard constraints. A missing/ambiguous scope does not widen search to unrelated content.
- Backend/runtime failure is not converted into “not found”.
- Candidate identity used for deduplication/stability is backend-controlled; application globals, mutable DOM attributes, visible text, and DOM index are not accepted as trusted physical identity.
- Late duplicate matches remain ambiguous before physical identity guards are applied.
- Action plans revalidate target and preconditions immediately before real execution.

These rules reduce wrong-target risk but do not prove business intent. Applications remain responsible for deciding whether an otherwise valid action should be authorized.

## Side effects and retries

The framework does not blindly retry the backend side effect of an action. Resolution may retry a typed `NOT_FOUND` according to explicit policy; stabilization and verification may poll read-only state after execution. A backend operation already invoked is represented as `REAL` even when it later fails, because a side effect may already exist.

This prevents automatic duplicate submissions/purchases, but it cannot guarantee that an external system did not commit a side effect before returning an error. Application-level idempotency keys or business reconciliation remain necessary where the external system supports them.

## Secrets and sensitive values

### Framework-owned secret types

Use `Secret` and secret workflow variables for credentials/tokens passed through supported secret-aware APIs. Framework-owned safe renderings redact those values as documented.

### Observation

Input values are opt-in. Passwords, common token/API-key controls, and payment-card controls remain redacted even when ordinary input values are enabled. Redacted observed values retain only safe disposition/presence metadata.

### Workflows

Workflow secret masking protects framework-owned incidental renderings. Explicit typed retrieval returns real values because an action may need them. Masking is not encryption, a vault, heap protection, or protection against application code that prints the secret itself.

### Recording

Recording (both schema V1 and V2) structurally excludes raw workflow inputs, raw output values, `ActionResult.value()`, observations/diagnostics, and raw `Throwable` data. It does **not** heuristically sanitize arbitrary identifiers. `RecordingId`, `ActionId`, workflow/step IDs, output-variable names (or, in V2, a published output's typed `WorkflowPlanOutput` name/type/secret-classification metadata - never a value), and similar metadata retained by the schema must be non-sensitive. Deterministic Replay (V2 only) validates and reconstructs a recorded decision trace without ever resolving a backend target or performing a side effect, so it introduces no new secret-handling surface beyond the recording it reads.

## Safe diagnostics and logging

Only a surface explicitly documented as safe or structural carries a safe-rendering guarantee. Examples include `Secret#toString()`, `ActionEvent#toString()`, selected compact renderings, and safe workflow/recording diagnostics.

Do not assume that every record's ordinary `toString()` is safe. Typed accessors may expose raw URLs, extracted values, page metadata, caller metadata, observations, or retained causes. Apply an application-owned redaction policy before logging, telemetry, persistence, or user display.

Raw `Throwable` messages/stack traces from browser/network/provider failures are particularly sensitive because third-party libraries can include URLs, headers, paths, or page-provided text.

## Plugins and custom SPIs

`PluginLoader` and custom SPIs run ordinary Java code in-process with the JVM permissions of the application.

There is no:

- bytecode sandbox;
- process isolation;
- plugin permission model;
- dependency resolver;
- network/filesystem restriction;
- execution timeout around arbitrary callback code;
- automatic recovery from JVM `Error` conditions.

Only load trusted plugin/provider code. Validation ensures contribution shape/order/identity; it does not make malicious code safe.

Plugin IDs, versions, strategy IDs, provider class names, and custom diagnostic metadata must also be treated as non-secret caller/provider metadata.

## JavaScript/page boundary

A hostile page can mutate its DOM, reorder nodes, duplicate semantics, navigate, detach frames, and race browser operations. Playwright-specific hardening therefore treats only proven disappearance as absence and propagates opaque backend failures. Internal identity state used to protect candidate selection is not derived from page-controlled globals or attributes.

`IPage.evaluate()` intentionally lets the application run JavaScript in the page and therefore bypasses semantic safety abstractions. Use it only when the application trusts the script and understands the page boundary.

## Files

Uploads validate paths through the action contract; downloads use an explicit destination/collision policy and prevent a suggested filename from escaping that destination. Applications remain responsible for whether a selected local file or destination directory is authorized and for the content of downloaded files.

Never execute downloaded content automatically based on a successful download result.

## Crawling and web policy

WebAgent4J does not claim legal, contractual, robots, rate-limit, or terms-of-service compliance merely because a crawl is technically possible. The caller is responsible for authorization, rate policy, and applicable site rules. The current crawlers are deterministic automation primitives, not a compliance engine.

## URL filter pattern safety

`CrawlRequest#includeUrlPattern`/`excludeUrlPattern` compile caller-supplied `java.util.regex.Pattern`s, evaluated by `HostScopePolicy` against every discovered URL before it enters the frontier. This is not remote regex injection - the caller authors the pattern, not an attacker - but a pathological pattern combined with a long, attacker-influenced discovered URL can still consume disproportionate CPU: Java's backtracking regex engine has no reliable, safe way to cancel a match already in progress, so a `Future`-with-timeout wrapper only abandons a still-running worker rather than actually stopping it.

`HostScopePolicy` bounds what it can bound safely: once at least one URL filter pattern is configured, a candidate URL longer than its internal maximum length is rejected before any pattern ever sees it, capping the worst-case input size. This is a genuine, deterministic bound on attacker-controlled input length - **not** a claim that every possible pattern is safe to evaluate even at that bounded length. A sufficiently pathological pattern (catastrophic nested quantifiers, for example) can still be expensive against a comparatively short string. Replacing `java.util.regex.Pattern` with a linear-time engine (RE2/J or similar) would close this residually but changes the supported regex syntax/semantics and adds a dependency - out of scope for a patch-level hardening change; it remains a candidate for a future, deliberately-versioned API design rather than something forced into this release.

The caller who configures a URL filter pattern is responsible for its complexity, exactly as they already are for its correctness. A crawl that configures no URL filter pattern at all is unaffected by any of this - there is no pattern for a URL to be evaluated against.

## Out of scope

The framework does not attempt to:

- bypass CAPTCHA, authentication, anti-bot systems, consent, or access controls;
- conceal browser fingerprints or rotate proxies;
- prevent a trusted in-process plugin from reading application memory/files/network;
- protect secrets from debuggers, heap dumps, JVM agents, or compromised hosts;
- validate arbitrary business authorization decisions;
- provide general malware scanning of downloaded content;
- provide universal SSRF isolation.

Report suspected vulnerabilities through the repository's private security-advisory process rather than a public issue.
