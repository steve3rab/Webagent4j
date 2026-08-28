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
 * <p>A policy that <em>does</em> implement this interface but returns {@link Optional#empty()} for
 * a destination it just allowed through {@link INetworkPolicy#evaluate} is different: a caller must
 * treat that as a fresh authorization failure for this connection attempt, never silently fall back
 * to an unpinned connection, since that would defeat the very guarantee this interface exists to
 * provide.
 */
public interface INetworkAddressAuthority {

    /**
     * Resolves and authorizes {@code destination} for one connection attempt.
     *
     * <p>Implementations perform at most one resolution per call - no hidden retry, exactly like
     * {@link INetworkAddressResolver} - and apply the same address-category rules {@link
     * INetworkPolicy#evaluate} would apply to the same hostname, so an ALLOW from {@code evaluate}
     * and a present result here stay consistent about which physical addresses are authorized.
     * Returns {@link Optional#empty()} when resolution fails, yields no addresses, the destination
     * fails any of the policy's own scheme/host/port/userinfo rules, or any resolved address is
     * denied by an address-category rule - fail closed, never a partially filtered address set.
     *
     * @param destination the textual destination to resolve and authorize; never itself resolved
     * @return the authorized address set, or {@link Optional#empty()} when authorization fails
     */
    Optional<VerifiedNetworkAddresses> authorizeConnection(NetworkDestination destination);
}
