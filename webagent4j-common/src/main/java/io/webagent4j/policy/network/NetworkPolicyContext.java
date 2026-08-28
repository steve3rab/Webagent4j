package io.webagent4j.policy.network;

import java.util.Objects;

/**
 * Immutable, structured description of one network request about to be authorized.
 *
 * @param kind what kind of request this is
 * @param destination the safe, canonicalized destination - see {@link NetworkDestination}
 * @param phase when this evaluation is happening relative to the actual request - see {@link
 *     NetworkCheckPhase}
 */
public record NetworkPolicyContext(
        NetworkRequestKind kind, NetworkDestination destination, NetworkCheckPhase phase) {

    /** Validates that every field is present. */
    public NetworkPolicyContext {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(destination, "destination");
        Objects.requireNonNull(phase, "phase");
    }
}
