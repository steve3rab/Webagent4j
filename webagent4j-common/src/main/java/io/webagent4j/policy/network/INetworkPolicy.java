package io.webagent4j.policy.network;

import io.webagent4j.policy.IExecutionPolicy;

/**
 * Authorizes one network request before it is sent (or, when the requesting backend cannot be
 * intercepted mid-flight - see {@link NetworkCheckPhase#POST_REQUEST} - checks it immediately
 * after). See {@link IExecutionPolicy} for the full evaluation contract.
 *
 * <p>Built-in implementations are produced by {@link NetworkPolicies}; a caller may also implement
 * this directly for fully custom logic, including inspecting {@link NetworkAddressCategory} results
 * from its own injected {@link INetworkAddressResolver}.
 */
@FunctionalInterface
public interface INetworkPolicy extends IExecutionPolicy<NetworkPolicyContext> {}
