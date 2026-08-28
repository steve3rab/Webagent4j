package io.webagent4j.policy.network;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The exact IP addresses a transport is authorized to connect to for one destination, in resolution
 * order, alongside the logical hostname it must still present for the {@code Host} header, TLS SNI,
 * and certificate hostname verification - the physical address is never a substitute for the
 * logical hostname in any of those three places.
 *
 * <p>Distinct from {@link NetworkDestination}: that type is purely textual (scheme, host, port) and
 * exists before any resolution happens, used to evaluate a policy's scheme/host/port/userinfo
 * rules. This type is only ever produced after a hostname has actually been resolved and every
 * resolved address has individually been confirmed not to be denied - it is the physical proof a
 * transport binds its connection to, closing the gap between "the address a policy checked" and
 * "the address the transport actually used" (DNS rebinding between check and connect).
 *
 * <p>{@code addresses} preserves resolution order (never re-sorted or deduplicated into a set) so a
 * transport that tries each address in turn behaves the same way ordinary DNS-driven connection
 * attempts would.
 *
 * @param host the logical hostname {@code addresses} were resolved for - never itself an address
 * @param port the destination port
 * @param addresses every resolved, individually-authorized address, in resolution order; never
 *     empty - a caller with nothing to authorize returns {@link java.util.Optional#empty()} from
 *     {@link INetworkAddressAuthority#authorizeConnection} instead of constructing this type with
 *     no addresses
 */
public record VerifiedNetworkAddresses(String host, int port, List<InetAddress> addresses) {

    /** Validates required fields and defensively copies {@code addresses}. */
    public VerifiedNetworkAddresses {
        Objects.requireNonNull(host, "host");
        if (host.isBlank()) {
            throw new IllegalArgumentException("host cannot be blank");
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("port must be between 1 and 65535, was " + port);
        }
        Objects.requireNonNull(addresses, "addresses");
        List<InetAddress> copy = new ArrayList<>(addresses.size());
        for (InetAddress address : addresses) {
            copy.add(Objects.requireNonNull(address, "addresses must not contain null"));
        }
        if (copy.isEmpty()) {
            throw new IllegalArgumentException("addresses cannot be empty");
        }
        addresses = List.copyOf(copy);
    }
}
