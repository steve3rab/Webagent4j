package io.webagent4j.policy.network;

import io.webagent4j.policy.PolicyReason;
import java.util.Locale;

/** Stable reason codes used by the built-in {@link NetworkPolicies} implementation. */
public final class NetworkPolicyReasons {

    public static final PolicyReason ALLOWED = PolicyReason.of("network.allowed");
    public static final PolicyReason SCHEME_DENIED = PolicyReason.of("network.scheme.denied");
    public static final PolicyReason HOST_DENIED = PolicyReason.of("network.host.denied");
    public static final PolicyReason PORT_DENIED = PolicyReason.of("network.port.denied");
    public static final PolicyReason USERINFO_DENIED = PolicyReason.of("network.userinfo.denied");
    public static final PolicyReason RESOLUTION_REQUIRED_BUT_UNRESOLVED =
            PolicyReason.of("network.resolution.required");
    public static final PolicyReason EVALUATION_FAILED =
            PolicyReason.of("network.policy.evaluation-failed");

    private NetworkPolicyReasons() {}

    /** Reason code for a resolved address matching a denied {@link NetworkAddressCategory}. */
    public static PolicyReason addressCategoryDenied(NetworkAddressCategory category) {
        return PolicyReason.of("network.address." + category.name().toLowerCase(Locale.ROOT));
    }
}
