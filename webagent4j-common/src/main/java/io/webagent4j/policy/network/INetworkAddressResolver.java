package io.webagent4j.policy.network;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;

/**
 * Resolves a hostname to the addresses it currently maps to. Injectable so tests can supply
 * deterministic, fake DNS results instead of ever performing a real lookup.
 *
 * <p><strong>Contract:</strong> one call to {@link #resolve(String)} performs at most one
 * resolution attempt - no hidden retry loop. A transient failure must be surfaced as a thrown
 * {@link UnknownHostException} (or another {@link RuntimeException}), never silently retried or
 * converted into an empty result.
 */
@FunctionalInterface
public interface INetworkAddressResolver {

    /**
     * Resolves {@code host} to every address it currently maps to.
     *
     * @param host a hostname - never called with an IP literal, since a literal's address is
     *     already known without resolution
     * @return the resolved addresses; may be empty if the host genuinely has none
     * @throws UnknownHostException if the host could not be resolved
     */
    List<InetAddress> resolve(String host) throws UnknownHostException;

    /**
     * Returns a resolver backed by the JVM's real system DNS resolution ({@link
     * InetAddress#getAllByName(String)}), performing one real lookup per call.
     */
    static INetworkAddressResolver system() {
        return host -> List.of(InetAddress.getAllByName(host));
    }
}
