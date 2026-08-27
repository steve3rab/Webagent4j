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
- `PLAN` - a non-authoritative snapshot captured by `plan()`, exposed via
  `IActionPlan#policyDecisions()`. It never gates anything; `IActionPlan#execute()` always
  re-evaluates every configured policy fresh, in `EXECUTE` mode, before any backend call.

Evaluation happens as late as this pipeline allows: after target resolution and preconditions, but
strictly before the dry-run short circuit and the real backend call. A `DENY`, a thrown exception,
and a malformed `null` decision are all treated identically - fail closed,
`ActionExecutionMode.NOT_EXECUTED`, zero backend invocations - classified as
`ActionFailureType.POLICY_DENIED` or `POLICY_EVALUATION_FAILED` respectively.

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
boolean flag - never the userinfo's actual content.

`NetworkPolicies.builder()` builds a declarative policy from allow-lists (`allowScheme`, `allowHost`
+ `includeSubdomains`, `allowPort`) and deny-by-category rules (`denyLoopback`,
`denyPrivateAddresses`, `denyLinkLocal`, `denyMulticast`, `denyUnspecified`, `denySharedAddresses`,
`denyDocumentationAddresses`, `denyBenchmarkAddresses`, `denyReservedAddresses`, `denyUserInfo`,
`requireResolutionForHostnames`). `NetworkAddressClassifier` classifies a resolved address into one
of nine categories using only `java.net` - no third-party CIDR library - covering every IPv4/IPv6
special-use range this project documents, including IPv4-mapped IPv6.

DNS resolution, via the injectable `INetworkAddressResolver`, happens **at most once per
evaluation**, only when at least one deny-category rule or `requireResolutionForHostnames()` is
configured, and never at all for a destination whose host is already an IP literal. A resolved
address that falls into any denied category denies the whole decision - a mixed
public-and-private resolution result denies, matching the "fail closed on any bad address" rule
rather than the "allow if at least one address is fine" rule. An empty resolution result under
`requireResolutionForHostnames()` also denies.

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
`EVALUATION_FAILED`, never a raw exception message or any other caller-supplied text.

`IActionPlan#policyDecisions()` returns the equivalent snapshot captured at `plan()` time, evaluated
in `ActionPolicyMode.PLAN`. It is purely informational: a `DENY` here does not block a plan from
being `READY`, and it can legitimately disagree with what `decisionTrace()` reports after
`execute()`, since page state - and a policy's own view of it - can change between the two.

## Compatibility

- The project version stays `1.1.0-SNAPSHOT`; the Revapi API-compatibility baseline stays `1.0.0`.
- No policy is configured by default anywhere. An action or crawl that never calls
  `.policy(...)`/`.networkPolicy(...)`/`withNetworkPolicy(...)` behaves identically to 1.0.
- No existing public record gained a new component. `ActionCommand` (an internal, package-private
  type) gained a field; `ActionResult`, `CrawlRequest`, `BrowserCrawlRequest`, and every other
  existing public record are unchanged in shape.
- Every new interface method (`IPreparedAction#policy`/`#networkPolicy`,
  `IActionPlan#policyDecisions`) is an additive default method.
- Every new enum constant (`ActionFailureType`, `ActionStage`, `CrawlFailureType`,
  `BrowserCrawlFailureType`) is additive; no existing constant was renamed or removed.

## What this is not

- **Not a general SSRF firewall.** DNS pre-resolution checks the addresses a hostname resolves to
  at evaluation time; it is not transport-level pinning and does not by itself defend against DNS
  rebinding between the check and the actual connection. `HttpCrawler` genuinely prevents a request
  before it is sent for every hop and every retry attempt it controls, but a governed `NAVIGATE`
  action can only detect - not prevent - a browser-internal redirect landing somewhere denied.
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
