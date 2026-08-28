/**
 * Network-destination governance: {@link io.webagent4j.policy.network.INetworkPolicy} decides
 * whether WebAgent4J may make a network request to a given destination, evaluated strictly before
 * the request is sent.
 *
 * <p>This is independent of, and complementary to, action authorization ({@code
 * io.webagent4j.action.policy}, {@code webagent4j-action}) and crawl-scope policy ({@code
 * ICrawlScopePolicy} in {@code webagent4j-crawler-api}, "should this URL be followed as part of the
 * crawl graph"). All configured gates must pass independently - a crawl-scope policy allowing a
 * host does not imply a network policy allows requesting whatever address that host currently
 * resolves to, and vice versa.
 *
 * <p><strong>Honest limitations</strong> (see {@code docs/governed-execution.md} for the full
 * discussion): this package prevents a request before it is sent whenever the pipeline evaluates
 * this policy first, which is true for {@code HttpCrawler} (every redirect hop) and for a governed
 * browser {@code NAVIGATE} action or crawl (the initial navigation). A browser's own internal
 * redirect handling cannot be intercepted mid-flight, so a redirect that lands somewhere the policy
 * would have denied is only detectable after that navigation already happened - reported
 * accordingly, never misrepresented as prevented. DNS pre-resolution checks the addresses a
 * hostname resolves to at evaluation time; it is not transport-level pinning and does not by itself
 * defend against DNS rebinding between check and connect. This package makes no claim to be a
 * complete SSRF firewall.
 */
package io.webagent4j.policy.network;
