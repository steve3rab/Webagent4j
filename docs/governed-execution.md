# Governed execution

Governed execution lets a caller attach policy to a prepared action before it runs: an
`IActionPolicy` decides whether the action's backend side effect may proceed at all, and an
`INetworkPolicy` decides whether a `NAVIGATE` action - or an `HttpCrawler`/`BrowserCrawler` request
- may reach a given network destination. Neither is configured by default; existing 1.0 behavior is
byte-for-byte unchanged unless a caller opts in.

This document is the authoritative reference for the feature. It complements, and does not replace,
[Actions](actions.md), [Security model](security-model.md), and [Limitations](limitations.md).

## Why this exists

Before this feature, WebAgent4J could resolve a target correctly and still perform an action a
caller never actually wanted performed - there was no framework-level seam to ask "should this
specific action, on this specific target, actually be allowed to run?" or "should WebAgent4J make a
network request to this specific destination?" before the backend was invoked. Governed execution
adds exactly those two seams, built on one small, synchronous, in-process contract shared by both.

## Core invariant

> Authorization must complete successfully before a side effect is allowed, and uncertainty must
> never be silently interpreted as permission. Once a side effect may have happened, WebAgent4J must
> never falsely report that it was not executed.

Everything below exists in service of those two rules.

## The policy contract

`io.webagent4j.policy.IExecutionPolicy<C>` (in `webagent4j-common`) is the generic shape both action
and network policies build on:

```java
public interface IExecutionPolicy<C> {
    PolicyDecision evaluate(C context);
}
```

- **Synchronous only.** An implementation must not block on network I/O, an MCP call, an LLM call, a
  database query, or any other remote round trip. WebAgent4J never makes an automatic remote
  decision call on a caller's behalf.
- **Two outcomes only.** `PolicyDecision` is `PolicyOutcome.ALLOW` or `PolicyOutcome.DENY`, each
  paired with a `PolicyReason` - a stable, grammar-restricted code (`[A-Za-z0-9][A-Za-z0-9._:-]{0,127}`),
  never free-form text. There is no `UNKNOWN`, `ASK`, or deferred outcome.
- **Fail closed.** A thrown exception, a returned `null`, or any other failure to produce a decision
  is treated identically to `DENY` by every pipeline this document describes - never as `ALLOW`.
- **No hidden retry.** A policy's own decision is never retried: a caller catches at most one
  `evaluate` outcome per real attempt and never re-invokes `evaluate` to second-guess a decision it
  already received. This is distinct from `HttpCrawler`'s HTTP-level retry policy, which is a
  separate, orthogonal mechanism: when a URL's real HTTP request is itself retried after a
  transport failure, `HttpCrawler` evaluates its network policy again immediately before *each* new
  real attempt - never reusing an earlier attempt's decision - because a policy that resolves
  hostnames may legitimately see a different answer after retry backoff. That is a fresh decision
  for a new attempt, not a retry of an existing one; see [HTTP crawler](http-crawler.md#retry). If a
  policy implementation itself needs to consult something unreliable, that unreliability must
  surface as an exception from that one evaluation, not be masked with an internal retry loop the
  caller cannot observe.
- **Untrusted, unsandboxed code.** A caller-supplied policy runs as ordinary Java code. WebAgent4J
  guarantees it will not invoke a governed backend before a policy allows it; it cannot prevent or
  undo a side effect a malicious or buggy policy performs itself during evaluation.

`ExecutionPolicies.allOf(...)` composes multiple policies of the same context type into one that
`ALLOW`s only if every one of them does, evaluated in the given order, short-circuiting at the first
`DENY` or thrown exception. An empty composition throws `IllegalArgumentException` rather than
silently behaving as allow-all.

## Action authorization

Configure with `IPreparedAction#policy(IActionPolicy)`. `IActionPolicy extends
IExecutionPolicy<ActionPolicyContext>`.

```java
ActionPolicyContext(
    ActionId actionId,
    ActionType actionType,
    ActionIdempotency idempotency,
    ActionSideEffect sideEffect,
    ActionPolicyMode mode,
    String targetDescription)
```

`ActionPolicyContext` is deliberately narrow: it never exposes the resolved DOM element, typed text,
a `Secret`, upload file contents, or any native backend object. A policy that needs a domain-specific
judgment must derive it from these structured fields - WebAgent4J does not infer business semantics
(such as "destructive" from button text) on a policy's behalf.

`ActionPolicyMode` distinguishes three evaluation contexts:

- `EXECUTE` - about to gate a real backend call.
- `DRY_RUN` - gates `dryRun()`'s validation; the backend is never called regardless, but a `DENY`
  still makes the dry run itself report failure.
- `PLAN` - a snapshot captured by `plan()`, exposed via `IActionPlan#policyDecisions()`. A `DENY` or
  evaluation failure seen here makes the plan itself `ActionPlanStatus.BLOCKED` rather than `READY`,
  since reporting `READY` over an action a configured policy has already refused would itself be a
  false authorization signal. It is still never a capability a caller can bank for later, though:
  `IActionPlan#execute()` always re-evaluates every configured policy completely fresh, in `EXECUTE`
  mode, before any backend call - a `READY` plan can still be denied at `execute()` if state changed,
  and a `BLOCKED` plan can still succeed if the blocking condition cleared.

Evaluation happens as late as this pipeline allows: after target resolution and preconditions, but
strictly before the dry-run short circuit and the real backend call. A `DENY`, a thrown exception,
and a malformed `null` decision are all treated identically - fail closed,
`ActionExecutionMode.NOT_EXECUTED`, zero backend invocations - classified as
`ActionFailureType.POLICY_DENIED` or `POLICY_EVALUATION_FAILED` respectively.

### Target identity binding

An `ALLOW` describes a specific, already-resolved target - never a standing authorization that
transfers to a different element merely because it satisfies the same semantic locator later. This
closes the window between "the policy authorized this concrete target" and "the backend side effect
runs against it": if the originally-resolved target is removed and a different one - matching the
exact same locator - takes its place before the backend call, that replacement is never acted on.

Immediately before the backend call, and only when an action policy is configured, WebAgent4J
revalidates that the target is still the exact same concrete, currently-attached element it was
when originally resolved, through `IElement#verifiedForExecution()`. A boolean-only recheck
(`IElement#isStillTheOriginallyResolvedTarget()`, still available for that narrower question) is
not enough on its own: a caller that re-resolves the element a second time to perform the actual
backend call - even immediately after a `true` answer - can still observe a different physical node
than the one just verified, if the DOM changed in between. `verifiedForExecution()` closes that
residual window by verifying identity and handing back a view bound to the exact same physical
handle in one atomic operation; the backend then acts through that returned view, never through a
second, independent resolution. The Playwright adapter implements this by capturing the live
`ElementHandle` during identity verification and consuming that exact handle for the native
operation (currently `click()`; every other Playwright action method still takes the older,
non-atomic path - see [Limitations](limitations.md)). `Optional.empty()` here - detachment,
replacement, ambiguity, or an inspection failure - is treated uniformly as "not proven," never as
"still the same." Failure to prove identity fails closed exactly like a policy `DENY`: zero backend
invocations, `ActionExecutionMode.NOT_EXECUTED`, classified as the additive
`ActionFailureType.TARGET_CHANGED` - distinct from `POLICY_DENIED` because the policy itself may
have allowed the original target, and distinct from `BACKEND_FAILURE` because the backend was never
invoked. This revalidation shares the action's own deadline; it is never given a fresh timeout of
its own, and there is no fallback re-resolution or retry against a different candidate. An
ungoverned action (no policy configured) never consults target identity at all, so its behavior and
cost are completely unchanged.

### Stabilization contract

`IStabilizationStrategy#await(...)` runs after the backend side effect has already executed and
before postcondition verification. Its result is never discarded: a `null` result, a
`StabilizationResult#stable()` of `false`, or the strategy itself throwing all produce a structured
`EXECUTION_FAILED` outcome (`ActionFailureType.STABILIZATION_FAILED`) rather than silently
proceeding to `SUCCESS`. `ActionExecutionMode` stays `REAL` in every one of these cases - the
backend already ran by the time stabilization is ever consulted, so it is never reported as
`NOT_EXECUTED` - and the backend is never invoked a second time; a stabilization failure is never
treated as a reason to retry the side effect. The `ActionStage.STABILIZATION_COMPLETED` ("stable")
event is only ever emitted on the genuinely-stable path, never on a failure this contract catches.

`ActionPolicies` provides standard declarative policies: `allowAll()`, `denyAll()`,
`allowOnlyTypes(...)`, `denyTypes(...)`, `allowOnlySideEffects(...)`, `denySideEffects(...)`,
`denyNonIdempotent()`, and `allOf(...)`. Every allow-list helper rejects an empty argument list
(an empty allow-list would otherwise deny everything, which is almost certainly not intended) and
denies any value not explicitly listed - including a future `ActionType`/`ActionSideEffect`
addition - rather than defaulting to `ALLOW`.

## Network-destination governance

Configure a `NAVIGATE` action with `IPreparedAction#networkPolicy(INetworkPolicy)`; configure
`HttpCrawler`/`BrowserCrawler` with `withNetworkPolicy(policy)`. `INetworkPolicy extends
IExecutionPolicy<NetworkPolicyContext>` (in `io.webagent4j.policy.network`, `webagent4j-common`).
Configuring `networkPolicy(...)` on any action type other than `NAVIGATE` is rejected immediately:
no other action type has a network destination knowable before its backend call.

```java
NetworkPolicyContext(
    NetworkRequestKind kind,     // BROWSER_NAVIGATION or HTTP_FETCH
    NetworkDestination destination,
    NetworkCheckPhase phase)     // PRE_REQUEST or POST_REQUEST
```

`NetworkDestination` is safe by construction, not just by a `toString()` convention: `of(URI)` never
even retains a request's userinfo, query, or fragment. It exposes only a lowercased/canonicalized
scheme, host (punycode-converted, trailing dot stripped), the resolved port, and a `hasUserInfo`
boolean flag - never the userinfo's actual content. Canonicalization lives in the record's own
canonical constructor, so it applies identically no matter which public entry point is used to
build one - `of(URI)` and calling the constructor directly always agree on the same scheme/host form
for the same logical destination; there is no separate, potentially-drifting canonicalization path
for direct construction. No canonicalization step ever performs DNS resolution, and subdomain
matching is exact label-boundary matching, never a naive string suffix check - `evil-example.com`
never matches an allow-listed `example.com`, with or without subdomains enabled.

`NetworkPolicies.builder()` builds a declarative policy from allow-lists (`allowScheme`, `allowHost`
+ `includeSubdomains`, `allowPort`) and deny-by-category rules (`denyLoopback`,
`denyPrivateAddresses`, `denyLinkLocal`, `denyMulticast`, `denyUnspecified`, `denySharedAddresses`,
`denyDocumentationAddresses`, `denyBenchmarkAddresses`, `denyReservedAddresses`, `denyUserInfo`,
`requireResolutionForHostnames`). `NetworkAddressClassifier` classifies a resolved address into one
of nine categories using only `java.net` - no third-party CIDR library - covering every IPv4/IPv6
special-use range this project documents, including IPv4-mapped IPv6.

DNS resolution, via the injectable `INetworkAddressResolver`, happens **at most once per
evaluation**, only when resolution is actually required for that evaluation, and never at all for a
destination whose host is already an IP literal. Resolution is required whenever
`requireResolutionForHostnames()` was configured explicitly **or** at least one address-category
deny rule (`denyLoopback`, `denyPrivateAddresses`, `denyLinkLocal`, `denyMulticast`,
`denyUnspecified`, `denySharedAddresses`, `denyDocumentationAddresses`, `denyBenchmarkAddresses`,
`denyReservedAddresses`) is configured - a category rule with no way to classify the address it is
supposed to gate is exactly the uncertainty the core invariant says must never be treated as
permission. A resolved address that falls into any denied category denies the whole decision - a
mixed public-and-private resolution result denies, matching the "fail closed on any bad address"
rule rather than the "allow if at least one address is fine" rule, regardless of which order the
addresses were returned in. Whenever resolution was required, an empty, `null`, or otherwise
unusable resolution result also denies - a resolver returning nothing is never treated as "nothing
to deny," since the framework cannot then prove the destination is safe. There are no automatic
resolver retries.

### Pre-request and post-request checks

For `HttpCrawler`, every real HTTP request - the crawl's own seed/discovered URL and every redirect
hop - is checked at `NetworkCheckPhase.PRE_REQUEST`, strictly before it is sent. A denied URL is
never fetched, never retried, and never counts against `CrawlRequest#maxPages()`'s fetch-identity
budget (`CrawlFailureType.NETWORK_POLICY_DENIED` / `NETWORK_POLICY_EVALUATION_FAILED`, both defined
with `attempts == 0`, alongside `CRAWL_LIMIT_REACHED` and `ALREADY_FETCHED`).

For a governed `NAVIGATE` action and for `BrowserCrawler`, the requested URL is checked at
`PRE_REQUEST` before the backend navigates. **A browser's own internal redirect handling cannot be
intercepted mid-flight** - unlike `HttpCrawler`, which fully controls its own redirect loop - so the
final URL is checked again at `NetworkCheckPhase.POST_REQUEST`, after navigation has already
happened. A `POST_REQUEST` failure - deny, thrown exception, or malformed decision alike - is always
reported as `ActionFailureType.POLICY_VIOLATION` with `ActionExecutionMode.REAL`
(`BrowserCrawlFailureType.NETWORK_POLICY_VIOLATION` for the crawler), **never** `NOT_EXECUTED`: the
navigation already genuinely happened, so it is never misreported as prevented. The page is still
recorded as a failure with no observation or link discovery performed on it.

### Transport-bound address pinning (`HttpCrawler` only)

DNS pre-resolution by itself only proves a hostname resolved to a safe address *at evaluation
time*; `java.net.http.HttpClient` (the JDK HTTP client `JavaHttpFetcher` builds on) then performs
its own, entirely independent resolution to actually open the connection, so nothing by default
ties the address a policy checked to the address a request is physically sent to - a DNS-rebinding
window between check and connect. `HttpCrawler` closes this window for the specific case where the
network policy can offer proof: `INetworkAddressAuthority` is a capability an `INetworkPolicy` may
additionally implement, exposing `authorizeConnection(NetworkDestination)`, which resolves and
individually category-checks a destination's addresses and returns a `VerifiedNetworkAddresses` -
the address set and the destination they were resolved for - or `Optional.empty()` when it cannot
confirm one. `NetworkPolicies.builder()`'s built-in declarative policy implements this
automatically; a fully custom `INetworkPolicy` lambda does not, and gets no pinning.

`HttpCrawler` requests this immediately before every real HTTP attempt - the crawl's own seed,
every redirect hop, and every retry - never reusing an earlier attempt's address set. When present,
`JavaHttpFetcher` routes the request through `PinnedSocketHttpTransport`, a minimal, `GET`-only
HTTP/1.1(+TLS) client built directly on `Socket`/`SSLSocket`: it connects to one of the verified
addresses while still sending the destination's logical hostname for the request line, `Host`
header, TLS SNI, and certificate hostname verification - the physical address is never a substitute
for a certificate matching the hostname, and hostname verification is never weakened or skipped.
Each connection is used for exactly one request (`Connection: close`, no pooling), so a pinned
connection can never be silently reused for a different destination.

When the configured policy allowed a destination through `evaluate()` but its
`authorizeConnection(...)` cannot re-confirm an address set for that same connection attempt, the
request is denied rather than silently falling back to an unpinned connection - a policy that
claims this capability is held to it. A policy that never implements `INetworkAddressAuthority`, or
no policy at all, keeps calling the exact same `IHttpFetcher#fetch(HttpFetchRequest)` overload as
before this capability existed: zero added cost or behavior change for that case.

This is deliberately scoped to `HttpCrawler`; browser navigation has no equivalent transport-level
seam to pin (see [What this is not](#what-this-is-not) and
[Browser crawler](browser-crawler.md#network-destination-policy)).

### Relationship to crawl-scope policy

Network-destination policy is independent of, and complementary to, crawl-scope policy
(`ICrawlScopePolicy`, "should this URL be followed as part of the crawl graph"). Both gates must
pass; neither implies the other. A crawl-scope policy can allow a host whose current address a
network policy denies (for example, a host that resolves to a loopback address) - in that case
scope says "follow this URL" and the network policy still says "do not actually request it", and no
fetch happens.

## Decision provenance

`ActionResult#decisionTrace()` returns an `ActionDecisionTrace` - the ordered sequence of
governed-execution decisions made while producing that result, in the exact order they were
evaluated (for example: action-policy pre-execution, network-policy pre-execution, network-policy
post-execution). It is derived lazily from the result's own `events()` on every call, never stored,
so an ungoverned action pays no cost for a trace it will never contain; every `ActionResult`
compatibility constructor - which never emits a policy event - naturally produces an empty trace.

Each `ActionDecisionEntry` carries only `kind` (`ACTION`/`NETWORK`), `phase`
(`PRE_EXECUTION`/`POST_EXECUTION`), `outcome` (`ALLOW`/`DENY`/`EVALUATION_FAILED`), and a
`PolicyReason` - the denying policy's own reason code on `DENY`, a stable built-in code on
`EVALUATION_FAILED`, never a raw exception message or any other caller-supplied text. No trace entry
ever contains a raw URI, userinfo, query, fragment, a `Secret`, or a native backend object -
`PolicyReason`'s grammar (`[A-Za-z0-9][A-Za-z0-9._:-]{0,127}`, no whitespace or control characters)
is enforced on every reason a policy returns, whether built in or caller-supplied.

`IActionPlan#policyDecisions()` returns the equivalent snapshot captured at `plan()` time, evaluated
in `ActionPolicyMode.PLAN`. A `DENY` or evaluation failure recorded here is exactly what makes the
plan `BLOCKED` (see [Action authorization](#action-authorization)); it is still only a snapshot,
though, never a capability - `execute()` always re-evaluates fresh, so this snapshot can legitimately
disagree with what `decisionTrace()` reports after `execute()`, since page state - and a policy's
own view of it - can change between the two.

### Safe diagnostics never copy arbitrary exception text

Every diagnostic this feature produces - `ActionDecisionEntry`, `ActionEvent`, `ActionFailure`,
`ActionResult`'s safe renderers, the crawler's own failure diagnostics - carries only a fixed
structural message and a `PolicyReason` code, never `exception.getMessage()` from a policy or
resolver `RuntimeException` appended into a rendered string. This is deliberate: this framework has
no way to know whether a given exception message contains a secret a caller's policy or resolver
happened to see - the raw cause remains available in-process (for example via
`ActionFailure#cause()`) for a caller who explicitly wants it, but it is never propagated into any
of this feature's own safe-by-default surfaces. Only genuine `RuntimeException`s are caught this
way; an `Error` (for example `OutOfMemoryError`) is never masked as a policy decision. The same
applies to a caller-supplied `PolicyReason` value: this feature cannot detect whether a
custom reason code contains a secret, so a policy that builds one from caller data is responsible
for keeping it safe to render - see [`PolicyReason`](#the-policy-contract)'s grammar restriction,
which limits length and character set but cannot verify semantic safety.

## Compatibility

- The project version stays `1.1.0-SNAPSHOT`; the Revapi API-compatibility baseline stays `1.0.0`.
- No policy is configured by default anywhere. An action or crawl that never calls
  `.policy(...)`/`.networkPolicy(...)`/`withNetworkPolicy(...)` behaves identically to 1.0.
- No existing public record gained a new component. `ActionCommand` (an internal, package-private
  type) gained a field; `ActionResult`, `CrawlRequest`, `BrowserCrawlRequest`, and every other
  existing public record are unchanged in shape.
- Every new interface method (`IPreparedAction#policy`/`#networkPolicy`,
  `IActionPlan#policyDecisions`, `IElement#isStillTheOriginallyResolvedTarget`/
  `#verifiedForExecution`, `IHttpFetcher#fetch(HttpFetchRequest, Optional<VerifiedNetworkAddresses>)`)
  is an additive default method; a backend that does not track physical element identity, or a
  fetcher that offers no pinning, needs no changes at all.
- Every new enum constant (`ActionFailureType` - including `TARGET_CHANGED` and
  `STABILIZATION_FAILED` - `ActionStage`, `CrawlFailureType`, `BrowserCrawlFailureType`) is
  additive, appended after every existing 1.0 constant so ordinal-sensitive code is unaffected; no
  existing constant was renamed, removed, or reordered.
- `INetworkAddressAuthority` and `VerifiedNetworkAddresses` are new, minimal, additive types in
  `io.webagent4j.policy.network`; no existing type in that package changed shape.

## What this is not

- **Not a general SSRF firewall.** `HttpCrawler` genuinely prevents a request before it is sent for
  every hop and every retry attempt it controls, and - only when the configured policy implements
  `INetworkAddressAuthority` (the built-in `NetworkPolicies` policy does; a fully custom
  `INetworkPolicy` does not unless it implements it too) - binds the actual transport connection to
  the exact addresses it verified (see
  [Transport-bound address pinning](#transport-bound-address-pinning-httpcrawler-only)). A governed
  `NAVIGATE` action or `BrowserCrawler` visit has no equivalent transport-level seam: it can only
  detect, never prevent, a browser-internal redirect landing somewhere denied, and its own DNS
  resolution for policy evaluation is never bound to whatever address the browser's own network
  stack ultimately connects to.
- **Not a policy sandbox.** A configured policy is ordinary, trusted, unsandboxed Java code, exactly
  like a plugin or custom SPI (see [Security model](security-model.md#plugins-and-custom-spis)).
  WebAgent4J cannot prevent a malicious policy from reading application memory or performing its own
  side effects during evaluation.
- **Not remote or AI-assisted authorization.** No policy in this feature calls an LLM, an MCP server,
  or any other remote decision service, and none is planned as part of this contract.
- **Direct legacy calls bypass governance.** Calling `IPage.navigate(...)` or `IBrowser`/`IPage`
  methods directly, outside a governed action or crawl, bypasses both action and network policy
  entirely - governance only applies to the paths this document describes.

## Interpreting a result

Three outcome shapes cover every governed-execution failure:

| Shape | `executionMode` | `executed()` | Meaning |
| --- | --- | --- | --- |
| Denied before execution | `NOT_EXECUTED` | `false` | The policy said no (or failed to evaluate) before any backend call. Nothing happened. |
| Target changed before execution | `NOT_EXECUTED` | `false` | The policy allowed the originally-resolved target, but its identity could not be reproven immediately before the backend call - `ActionFailureType.TARGET_CHANGED`. Nothing happened. |
| Backend failure after allow | `REAL` | `true` | The policy allowed it; the backend itself then failed. Not the policy's fault - see `ActionFailureType.BACKEND_FAILURE`/`UPLOAD_FAILURE`/`DOWNLOAD_FAILURE`. |
| Post-navigation violation | `REAL` | `true` | Navigation already happened; only the *final* URL was denied (or failed to evaluate), which could only be detected afterward - `ActionFailureType.POLICY_VIOLATION`. |

`result.executed()` is the one signal that answers "did a side effect possibly already happen" -
always consult it before deciding whether it would be safe to retry.

## A complete example

```java
import io.webagent4j.action.policy.ActionPolicies;
import io.webagent4j.policy.network.NetworkPolicies;

var networkPolicy = NetworkPolicies.builder()
        .allowScheme("https")
        .allowHost("example.com")
        .includeSubdomains(true)
        .denyLoopback()
        .denyPrivateAddresses()
        .denyLinkLocal()
        .build();

var actionPolicy = ActionPolicies.allOf(
        ActionPolicies.denyNonIdempotent(),
        ActionPolicies.allowOnlyTypes(ActionType.CLICK, ActionType.NAVIGATE));

ActionResult<Void> result = page.action()
        .navigate("https://example.com/checkout")
        .policy(actionPolicy)
        .networkPolicy(networkPolicy)
        .execute();

if (!result.success()) {
    for (ActionDecisionEntry decision : result.decisionTrace().entries()) {
        System.out.println(decision.kind() + "/" + decision.phase() + " -> "
                + decision.outcome() + " (" + decision.reason().code() + ")");
    }
}
```

This example uses the final public API exactly as shipped (imports for `ActionType` and
`ActionDecisionEntry` omitted for brevity, matching this guide's other snippets).

## See also

- [Actions](actions.md) - the base action pipeline governed execution extends.
- [Security model](security-model.md) - the framework-wide trust boundary.
- [Limitations](limitations.md) - current product-level limitations.
- [HTTP crawler](http-crawler.md) and [Browser crawler](browser-crawler.md) - the crawl engines
  `withNetworkPolicy(...)` attaches to.
