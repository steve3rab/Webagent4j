package io.webagent4j.policy.network;

import io.webagent4j.policy.PolicyDecision;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Builds standard, declarative {@link INetworkPolicy} implementations from allow-lists and
 * deny-by-category rules, so most callers never need to implement {@link INetworkPolicy}
 * themselves.
 *
 * <p>An allow-list that is never configured places no restriction of that kind (an empty {@code
 * allowSchemes} configuration means every scheme is allowed, not none) - this is a builder for
 * <em>additional</em> restrictions, not a policy that starts by denying everything. A deny-category
 * rule is only ever evaluated for a destination the framework actually resolved: DNS resolution
 * happens at most once per evaluation, only when at least one deny-category rule or {@link
 * Builder#requireResolutionForHostnames()} is configured, and never at all for a destination whose
 * host is already an IP literal (its address is already known without a lookup) or when no category
 * rule applies. This keeps an ungoverned or purely allow-list-based policy free of any new DNS
 * cost.
 */
public final class NetworkPolicies {

    private NetworkPolicies() {}

    /**
     * Starts a builder using real system DNS resolution ({@link INetworkAddressResolver#system()}).
     */
    public static Builder builder() {
        return new Builder(INetworkAddressResolver.system());
    }

    /** Starts a builder using the given resolver - the seam tests use to fake DNS results. */
    public static Builder builder(INetworkAddressResolver resolver) {
        return new Builder(resolver);
    }

    /** Mutable builder for a declarative {@link INetworkPolicy}. */
    public static final class Builder {

        private final INetworkAddressResolver resolver;
        private final Set<String> allowSchemes = new LinkedHashSet<>();
        private final Set<String> allowHosts = new LinkedHashSet<>();
        private final Set<Integer> allowPorts = new LinkedHashSet<>();
        private final EnumSet<NetworkAddressCategory> deniedCategories =
                EnumSet.noneOf(NetworkAddressCategory.class);
        private boolean includeSubdomains;
        private boolean denyUserInfo;
        private boolean requireResolutionForHostnames;

        private Builder(INetworkAddressResolver resolver) {
            this.resolver = Objects.requireNonNull(resolver, "resolver");
        }

        /** Restricts requests to the given scheme (e.g. {@code "https"}), case-insensitively. */
        public Builder allowScheme(String scheme) {
            allowSchemes.add(requireNonBlankLower(scheme, "scheme"));
            return this;
        }

        /**
         * Restricts requests to the given host, canonicalized exactly as {@link
         * NetworkDestination#of(java.net.URI)} canonicalizes a real request's destination host
         * (lowercase, trailing dot removed, IDN/punycode-normalized) so a configured host and a
         * request's actual destination are always compared on identical terms.
         *
         * @throws IllegalArgumentException if {@code host} is blank or is not a syntactically valid
         *     hostname
         */
        public Builder allowHost(String host) {
            Objects.requireNonNull(host, "host");
            allowHosts.add(HostCanonicalizer.canonicalizeStrict(host));
            return this;
        }

        /**
         * Whether an allowed host's subdomains are also allowed - e.g. {@code
         * allowHost("example.com")} with this enabled also allows {@code "sub.example.com"}, but
         * never {@code "evil-example.com"} (a real subdomain boundary, not a suffix match).
         */
        public Builder includeSubdomains(boolean value) {
            this.includeSubdomains = value;
            return this;
        }

        /** Restricts requests to the given port. */
        public Builder allowPort(int port) {
            allowPorts.add(requireValidPort(port));
            return this;
        }

        /** Denies a destination whose userinfo component ({@code user:pass@}) is present. */
        public Builder denyUserInfo() {
            this.denyUserInfo = true;
            return this;
        }

        public Builder denyLoopback() {
            deniedCategories.add(NetworkAddressCategory.LOOPBACK);
            return this;
        }

        public Builder denyPrivateAddresses() {
            deniedCategories.add(NetworkAddressCategory.PRIVATE);
            return this;
        }

        public Builder denyLinkLocal() {
            deniedCategories.add(NetworkAddressCategory.LINK_LOCAL);
            return this;
        }

        public Builder denyMulticast() {
            deniedCategories.add(NetworkAddressCategory.MULTICAST);
            return this;
        }

        public Builder denyUnspecified() {
            deniedCategories.add(NetworkAddressCategory.UNSPECIFIED);
            return this;
        }

        public Builder denySharedAddresses() {
            deniedCategories.add(NetworkAddressCategory.SHARED);
            return this;
        }

        public Builder denyDocumentationAddresses() {
            deniedCategories.add(NetworkAddressCategory.DOCUMENTATION);
            return this;
        }

        public Builder denyBenchmarkAddresses() {
            deniedCategories.add(NetworkAddressCategory.BENCHMARK);
            return this;
        }

        public Builder denyReservedAddresses() {
            deniedCategories.add(NetworkAddressCategory.RESERVED);
            return this;
        }

        /**
         * Requires that every hostname (never an IP literal) resolve to at least one address before
         * being allowed - an empty or failed resolution denies. Implied by any {@code deny*}({@link
         * NetworkAddressCategory}) rule, since those rules cannot be evaluated without resolving;
         * this method exists for a policy that wants resolution enforced with no category rule
         * configured.
         */
        public Builder requireResolutionForHostnames() {
            this.requireResolutionForHostnames = true;
            return this;
        }

        /** Builds the immutable, declarative {@link INetworkPolicy}. */
        public INetworkPolicy build() {
            return new DeclarativeNetworkPolicy(
                    resolver,
                    Set.copyOf(allowSchemes),
                    Set.copyOf(allowHosts),
                    includeSubdomains,
                    Set.copyOf(allowPorts),
                    denyUserInfo,
                    EnumSet.copyOf(
                            deniedCategories.isEmpty()
                                    ? EnumSet.noneOf(NetworkAddressCategory.class)
                                    : deniedCategories),
                    requireResolutionForHostnames);
        }

        private static String requireNonBlankLower(String value, String name) {
            Objects.requireNonNull(value, name);
            if (value.isBlank()) {
                throw new IllegalArgumentException(name + " cannot be blank");
            }
            return value.toLowerCase(Locale.ROOT);
        }

        private static int requireValidPort(int port) {
            if (port < 1 || port > 65535) {
                throw new IllegalArgumentException("port must be between 1 and 65535, was " + port);
            }
            return port;
        }
    }

    /** The immutable policy a {@link Builder} produces. */
    private static final class DeclarativeNetworkPolicy
            implements INetworkPolicy, INetworkAddressAuthority {

        private static final Pattern IPV4_LITERAL =
                Pattern.compile("^\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}$");

        private final INetworkAddressResolver resolver;
        private final Set<String> allowSchemes;
        private final Set<String> allowHosts;
        private final boolean includeSubdomains;
        private final Set<Integer> allowPorts;
        private final boolean denyUserInfo;
        private final EnumSet<NetworkAddressCategory> deniedCategories;
        private final boolean requireResolutionForHostnames;

        DeclarativeNetworkPolicy(
                INetworkAddressResolver resolver,
                Set<String> allowSchemes,
                Set<String> allowHosts,
                boolean includeSubdomains,
                Set<Integer> allowPorts,
                boolean denyUserInfo,
                EnumSet<NetworkAddressCategory> deniedCategories,
                boolean requireResolutionForHostnames) {
            this.resolver = resolver;
            this.allowSchemes = allowSchemes;
            this.allowHosts = allowHosts;
            this.includeSubdomains = includeSubdomains;
            this.allowPorts = allowPorts;
            this.denyUserInfo = denyUserInfo;
            this.deniedCategories = deniedCategories;
            this.requireResolutionForHostnames = requireResolutionForHostnames;
        }

        @Override
        public PolicyDecision evaluate(NetworkPolicyContext context) {
            Objects.requireNonNull(context, "context");
            NetworkDestination destination = context.destination();

            if (!allowSchemes.isEmpty() && !allowSchemes.contains(destination.scheme())) {
                return PolicyDecision.deny(NetworkPolicyReasons.SCHEME_DENIED);
            }
            if (!allowHosts.isEmpty() && !hostAllowed(destination.host())) {
                return PolicyDecision.deny(NetworkPolicyReasons.HOST_DENIED);
            }
            if (!allowPorts.isEmpty() && !allowPorts.contains(destination.port())) {
                return PolicyDecision.deny(NetworkPolicyReasons.PORT_DENIED);
            }
            if (denyUserInfo && destination.hasUserInfo()) {
                return PolicyDecision.deny(NetworkPolicyReasons.USERINFO_DENIED);
            }

            if (!deniedCategories.isEmpty() || requireResolutionForHostnames) {
                PolicyDecision categoryDecision = evaluateAddressCategories(destination.host());
                if (categoryDecision != null) {
                    return categoryDecision;
                }
            }

            return PolicyDecision.allow(NetworkPolicyReasons.ALLOWED);
        }

        /**
         * Returns a DENY decision, or {@code null} if address-category checks did not deny.
         *
         * <p>This method is only ever called when resolution is required for evaluation - either
         * {@code requireResolutionForHostnames} is set, or at least one deny-category rule is
         * configured (see the call site's guard). In either case, an empty resolution means
         * classification is genuinely impossible, not that nothing was found to deny: a
         * deny-category rule cannot be proven satisfied without at least one resolved address to
         * check it against, so an empty result fails closed exactly like an explicit {@code
         * requireResolutionForHostnames()} unresolved host does - regardless of whether that method
         * was itself called.
         */
        private PolicyDecision evaluateAddressCategories(String host) {
            AddressResolution resolution = resolveAndClassify(host);
            if (!resolution.resolvedAtLeastOne()) {
                return PolicyDecision.deny(NetworkPolicyReasons.RESOLUTION_REQUIRED_BUT_UNRESOLVED);
            }
            for (InetAddress address : resolution.addresses()) {
                NetworkAddressCategory category = NetworkAddressClassifier.classify(address);
                if (deniedCategories.contains(category)) {
                    return PolicyDecision.deny(
                            NetworkPolicyReasons.addressCategoryDenied(category));
                }
            }
            return null;
        }

        /**
         * Resolves {@code host} - or parses it as an already-known IP literal - exactly once,
         * shared by both {@link #evaluateAddressCategories} and {@link #authorizeConnection} so
         * neither can drift from the other about which addresses a given hostname resolved to
         * within one call.
         */
        private AddressResolution resolveAndClassify(String host) {
            InetAddress literal = parseLiteral(host);
            if (literal != null) {
                return AddressResolution.resolved(List.of(literal));
            }
            List<InetAddress> addresses;
            try {
                addresses = resolver.resolve(host);
            } catch (UnknownHostException unresolved) {
                throw new NetworkResolutionFailedException(
                        "could not resolve network destination host", unresolved);
            }
            if (addresses == null) {
                throw new NetworkResolutionFailedException(
                        "resolver returned a null address list", null);
            }
            if (addresses.isEmpty()) {
                return AddressResolution.unresolved();
            }
            for (InetAddress address : addresses) {
                if (address == null) {
                    throw new NetworkResolutionFailedException(
                            "resolver returned a null address entry", null);
                }
            }
            return AddressResolution.resolved(List.copyOf(addresses));
        }

        /**
         * Resolves and authorizes {@code destination} for one connection attempt, so a transport
         * can pin its actual connection to precisely the addresses this method just individually
         * confirmed are not denied - never a second, independent resolution that could silently
         * observe a different result. Applies the exact same scheme/host/port/userinfo and
         * address-category rules {@link #evaluate} applies, reading the same instance fields, so an
         * ALLOW from {@code evaluate} and a present result here never disagree about which
         * destinations and addresses are authorized.
         *
         * <p>Unlike {@link #evaluate}, this always resolves a non-literal host - regardless of
         * whether any {@code deny*} category rule is configured - since producing a verified
         * address set is this method's entire purpose. A policy built purely from
         * scheme/host/port/userinfo allow-list rules therefore incurs one additional DNS resolution
         * per connection attempt once a caller starts requesting transport pinning, versus the
         * zero-DNS fast path {@link #evaluate} alone still takes for such a policy.
         */
        @Override
        public Optional<VerifiedNetworkAddresses> authorizeConnection(
                NetworkDestination destination) {
            Objects.requireNonNull(destination, "destination");
            if (!allowSchemes.isEmpty() && !allowSchemes.contains(destination.scheme())) {
                return Optional.empty();
            }
            if (!allowHosts.isEmpty() && !hostAllowed(destination.host())) {
                return Optional.empty();
            }
            if (!allowPorts.isEmpty() && !allowPorts.contains(destination.port())) {
                return Optional.empty();
            }
            if (denyUserInfo && destination.hasUserInfo()) {
                return Optional.empty();
            }
            AddressResolution resolution;
            try {
                resolution = resolveAndClassify(destination.host());
            } catch (NetworkResolutionFailedException unresolvable) {
                return Optional.empty();
            }
            if (!resolution.resolvedAtLeastOne()) {
                return Optional.empty();
            }
            for (InetAddress address : resolution.addresses()) {
                if (deniedCategories.contains(NetworkAddressClassifier.classify(address))) {
                    return Optional.empty();
                }
            }
            return Optional.of(
                    new VerifiedNetworkAddresses(
                            destination.host(), destination.port(), resolution.addresses()));
        }

        /** At least one resolved address, or none - {@code addresses} is empty only when not. */
        private record AddressResolution(boolean resolvedAtLeastOne, List<InetAddress> addresses) {
            static AddressResolution unresolved() {
                return new AddressResolution(false, List.of());
            }

            static AddressResolution resolved(List<InetAddress> addresses) {
                return new AddressResolution(true, addresses);
            }
        }

        private boolean hostAllowed(String host) {
            if (allowHosts.contains(host)) {
                return true;
            }
            if (!includeSubdomains) {
                return false;
            }
            for (String allowed : allowHosts) {
                if (host.endsWith("." + allowed)) {
                    return true;
                }
            }
            return false;
        }

        /**
         * Parses {@code host} as an IPv4 or IPv6 literal without ever performing DNS resolution.
         * {@link InetAddress#getByName(String)} does not resolve a syntactically valid numeric
         * address - it is only ever called here once the text already looks like one - so a genuine
         * hostname never reaches the resolver through this path.
         */
        private static InetAddress parseLiteral(String host) {
            boolean looksLikeIpv4 = IPV4_LITERAL.matcher(host).matches();
            boolean looksLikeIpv6 = host.indexOf(':') >= 0;
            if (!looksLikeIpv4 && !looksLikeIpv6) {
                return null;
            }
            try {
                InetAddress candidate = InetAddress.getByName(host);
                return candidate;
            } catch (UnknownHostException notALiteral) {
                return null;
            }
        }
    }

    /**
     * Thrown when network-address resolution itself fails during evaluation - caught by the
     * governed pipeline exactly like any other {@link RuntimeException} thrown from {@link
     * INetworkPolicy#evaluate}, so it fails closed identically to a {@code DENY}.
     */
    public static final class NetworkResolutionFailedException extends RuntimeException {
        NetworkResolutionFailedException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
