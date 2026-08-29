package io.webagent4j.policy.network;

import java.util.Optional;

/**
 * Capability an {@link INetworkPolicy} may additionally implement to expose the exact, freshly
 * resolved and individually-authorized IP address set for one destination, so a transport can bind
 * its actual connection to that same set instead of performing its own, independent resolution that
 * could silently observe a different result between the check and the connection - the DNS
 * rebinding / time-of-check-to-time-of-use gap this interface exists to close.
 *
 * <p>Not every {@link INetworkPolicy} can offer this: a fully custom policy that never resolves
 * addresses at all has nothing to authorize, and simply does not implement this interface. A caller
 * that finds no {@link INetworkAddressAuthority} for its configured policy falls back to an
 * ordinary, unpinned connection - no worse than this package's pre-existing behavior, and
 * documented in {@code package-info.java} as this package's honest boundary: it is not a complete
 * SSRF firewall for arbitrary policies.
 *
 * <p>For a policy that implements this interface, {@link #authorizeConnection} is the complete
 * authorization decision for one connection attempt on its own - a caller does not, and must not,
 * also call {@link INetworkPolicy#evaluate} for that same attempt first. Calling both would resolve
 * the destination twice, leaving a real (if narrow) window for a DNS-rebinding attacker to answer
 * the two resolutions differently; {@link #authorizeConnection} already applies every rule {@code
 * evaluate} would; a present result here is exactly as authoritative as an ALLOW from {@code
 * evaluate} would have been, at the cost of at most one resolver observation for the attempt. A
 * caller that receives {@link Optional#empty()} from {@link #authorizeConnection} must treat that
 * as this connection attempt being denied - fail closed, never a silent fall back to an unpinned
 * connection, since that would defeat the very guarantee this interface exists to provide.
 */
public interface INetworkAddressAuthority {

    /**
     * Resolves and authorizes {@code destination} for one connection attempt - the complete
     * authorization decision for that attempt, on its own.
     *
     * <p>Implementations perform at most one resolution per call - no hidden retry, exactly like
     * {@link INetworkAddressResolver} - and apply the same scheme/host/port/userinfo and
     * address-category rules {@link INetworkPolicy#evaluate} would apply to the same destination,
     * so this call needs no separate, preceding {@code evaluate} call for the same attempt: a
     * present result here already means this exact address set is authorized, exactly as an ALLOW
     * from {@code evaluate} would have meant. Returns {@link Optional#empty()} when resolution
     * fails, yields no addresses, the destination fails any of the policy's own
     * scheme/host/port/userinfo rules, or any resolved address is denied by an address-category
     * rule - fail closed, never a partially filtered address set; a caller treats an empty result
     * as the connection being denied.
     *
     * @param destination the textual destination to resolve and authorize; never itself resolved
     * @return the authorized address set, or {@link Optional#empty()} when authorization fails
     */
    Optional<VerifiedNetworkAddresses> authorizeConnection(NetworkDestination destination);
}
