# HTTP crawler

The HTTP crawler explores HTTP(S) pages without a browser, JavaScript execution, or AI. It is a deterministic sequential BFS engine built around backend-neutral crawler contracts and a JDK HTTP implementation.

## Quick start

```java
CrawlRequest request = CrawlRequest.builder()
        .seed("https://example.com/")
        .maxDepth(2)
        .maxPages(50)
        .build();

CrawlResult result = new HttpCrawler().crawl(request);
```

## Determinism

For the same request, normalized seed order, injected timing, and HTTP responses/failures, logical crawl behavior is deterministic: frontier/discovery order, normalization, deduplication, scope/redirect/retry decisions, result ordering, statistics, and termination classification.

Real fetch duration and raw `Throwable` object identity are not deterministic equality guarantees.

## Request bounds

`CrawlRequest` validates required positive/finite limits and HTTP(S) seeds at construction. Important controls include depth, fetch-identity page budget, host/subdomain scope, allowed schemes, request timeout, maximum response bytes, redirect limit, retry policy/statuses, headers/user agent, allowed content types, query-parameter policy, include/exclude URL patterns, and fail-fast.

Unsupported traversal modes are rejected rather than silently downgraded.

## URL normalization

Normalization is deterministic/idempotent: scheme/host casing, fragments, default ports, path-dot segments, empty path, and configured query-parameter policy are handled explicitly. The normalizer does not arbitrarily upgrade schemes or guess that two distinct URLs are equivalent.

Known tracking-parameter dropping is deliberately conservative and configurable.

## Discovery versus fetch identity

The crawler distinguishes:

- discovery identity — normalized URLs discovered from seeds/links;
- fetch identity — normalized URLs for which an HTTP request is actually claimed, including redirect hops.

`maxPages` authoritatively bounds fetch identity. Every real request/redirect hop must claim budget before the request. A converging redirect to an already-fetched identity is not fetched again merely to fabricate another page result.

This distinction means discovered URL count and fetched URL count are not required to be equal.

## Scope

Scope evaluation checks scheme, host/subdomain/allowed hosts, and include/exclude patterns with explicit decision reasons. Subdomain checks use a dot boundary and do not accept lookalike hosts such as `evil-example.com` for `example.com`.

No Public Suffix List is used; the caller chooses literal seeds/allowed hosts. This is not a general SSRF firewall.

## Redirects

Redirect handling is bounded per attempt. Each redirect target is normalized, scope-checked, and fetch-budget/dedup-checked before being requested. Redirect chains remain observable in crawler result/failure structures where supported.

A redirect does not silently escape scope or page budget.

## Network-destination policy

`HttpCrawler#withNetworkPolicy(policy)` returns a crawler that checks a configured `INetworkPolicy`
against every real HTTP request attempt - the crawl's own seed, every discovered URL, every redirect
hop, and every retry of any of those - strictly before that specific attempt is sent. There is no
hidden retry of the policy decision itself and no reuse of a stale decision across attempts: each
real network attempt gets its own fresh evaluation, since a policy that resolves hostnames may
legitimately see a different answer after retry backoff.

A URL denied on its very first attempt is never fetched at all and never counts against
`maxPages()`'s fetch-identity budget (`attempts == 0` in the resulting failure). A URL denied on a
*retry* is different: one or more real requests for it were already sent and already counted against
the fetch-identity budget before the policy denied a later retry - that budget consumption is not
undone, and the resulting failure's attempt count honestly reflects the real requests already made
(`attempts >= 1`), not zero. Either way, once a retry is denied no further retry of that URL is ever
attempted. Not configuring a policy leaves this crawler's behavior unchanged. See [Governed
execution](governed-execution.md).

By default this mechanism authorizes *destinations*, not resolved addresses: it does not by itself
provide complete protection against DNS-rebinding attacks, where a hostname resolves to one address
at policy-evaluation time and a different address by the time the underlying HTTP client actually
connects. When the configured policy also implements `INetworkAddressAuthority` - the built-in
`NetworkPolicies` policy does automatically - this crawler closes that window instead of merely
narrowing it: it binds the actual transport connection to the exact, freshly re-verified address set
the policy offers for that specific attempt, using a dedicated pinned-socket transport rather than
letting the JDK's `HttpClient` resolve the destination independently. See [Governed
execution](governed-execution.md#transport-bound-address-pinning-httpcrawler-only) for the full
contract, including the (deliberate) case where a custom `INetworkPolicy` that does not implement
this capability still only narrows, rather than eliminates, the window.

## Retry

Retries are explicit HTTP fetch policy, not a general framework side-effect retry. Retryable transport failures/statuses follow `RetryPolicy` and configured status codes. Backoff arithmetic is finite/saturating; interruption is not swallowed. When a network policy is configured, every retry attempt is freshly re-authorized immediately before it is sent (see above) - a retry never reuses the authorization decision made for an earlier attempt of the same URL.

## Response size/content

Response size is bounded while streaming rather than only after full buffering. Content-type policy determines whether a successful response is parsed for HTML links. Non-HTML allowed/unsupported responses are classified according to crawler policy rather than fed through an HTML parser blindly.

## Parsing and links

HTML link extraction is deterministic document-order parsing. Malformed/unsupported links are rejected with decisions rather than destabilizing frontier order. Declared canonical URLs are observed metadata and do not replace crawl identity automatically.

## Failures and partial results

By default, one URL failure is recorded and sibling frontier work continues. `failFast` makes termination explicit. A backend failure is never fabricated into an HTTP response or an empty success.

Raw failure causes, URLs, and response-related data may be sensitive; do not log ordinary result/failure records without applying application policy.

## Security and policy limitations

- no JavaScript/browser session;
- no `robots.txt` enforcement;
- no universal SSRF/private-network protection;
- no distributed/high-concurrency mode;
- no automatic browser fallback;
- no legal/terms/rate-policy compliance engine.

See [Security model](security-model.md) and [Known limitations](limitations.md).
