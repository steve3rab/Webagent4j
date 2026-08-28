package io.webagent4j.policy.network;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.webagent4j.policy.PolicyDecision;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Behavioral matrix for {@link NetworkPolicies}: allow-list gates, address-category deny rules
 * driven entirely by a fake, deterministic {@link INetworkAddressResolver} (never a real DNS
 * lookup), and the no-hidden-retry / no-DNS-for-literals / no-DNS-when-ungoverned guarantees.
 */
class NetworkPoliciesTest {

    @Test
    void defaultBuiltPolicyAllowsEverythingWhenNoRestrictionIsConfigured() {
        INetworkPolicy policy = NetworkPolicies.builder().build();
        assertThat(policy.evaluate(context("https://example.com/")).isAllow()).isTrue();
    }

    @Test
    void allowSchemeRejectsAnyOtherScheme() {
        INetworkPolicy policy = NetworkPolicies.builder().allowScheme("https").build();
        assertThat(policy.evaluate(context("https://example.com/")).isAllow()).isTrue();
        PolicyDecision denied = policy.evaluate(context("http://example.com/"));
        assertThat(denied.isDeny()).isTrue();
        assertThat(denied.reason()).isEqualTo(NetworkPolicyReasons.SCHEME_DENIED);
    }

    @Test
    void allowHostRejectsAnyOtherHost() {
        INetworkPolicy policy = NetworkPolicies.builder().allowHost("example.com").build();
        assertThat(policy.evaluate(context("https://example.com/")).isAllow()).isTrue();
        assertThat(policy.evaluate(context("https://attacker.test/")).isDeny()).isTrue();
    }

    @Test
    void subdomainIsAllowedOnlyWhenIncludeSubdomainsIsEnabled() {
        INetworkPolicy restrictive = NetworkPolicies.builder().allowHost("example.com").build();
        assertThat(restrictive.evaluate(context("https://sub.example.com/")).isDeny()).isTrue();

        INetworkPolicy permissive =
                NetworkPolicies.builder().allowHost("example.com").includeSubdomains(true).build();
        assertThat(permissive.evaluate(context("https://sub.example.com/")).isAllow()).isTrue();
    }

    @Test
    void suffixLookalikeHostIsNeverConfusedWithARealSubdomain() {
        INetworkPolicy policy =
                NetworkPolicies.builder().allowHost("example.com").includeSubdomains(true).build();
        assertThat(policy.evaluate(context("https://evil-example.com/")).isDeny()).isTrue();
    }

    @Test
    void allowPortRejectsAnyOtherPort() {
        INetworkPolicy policy = NetworkPolicies.builder().allowPort(443).build();
        assertThat(policy.evaluate(context("https://example.com/")).isAllow()).isTrue();
        assertThat(policy.evaluate(context("https://example.com:8443/")).isDeny()).isTrue();
    }

    @Test
    void denyUserInfoRejectsAUrlCarryingCredentials() {
        INetworkPolicy policy = NetworkPolicies.builder().denyUserInfo().build();
        assertThat(policy.evaluate(context("https://example.com/")).isAllow()).isTrue();
        assertThat(policy.evaluate(context("https://user:pass@example.com/")).isDeny()).isTrue();
    }

    @Test
    void denyLoopbackDeniesAnIpLiteralWithoutEverCallingTheResolver() {
        INetworkAddressResolver resolverThatMustNeverBeCalled =
                host -> {
                    throw new AssertionError(
                            "resolver must never be called for an IP literal destination");
                };
        INetworkPolicy policy =
                NetworkPolicies.builder(resolverThatMustNeverBeCalled).denyLoopback().build();

        assertThat(policy.evaluate(context("http://127.0.0.1/")).isDeny()).isTrue();
    }

    @Test
    void denyPrivateAddressesDeniesAHostnameResolvingToAPrivateAddress()
            throws UnknownHostException {
        INetworkAddressResolver fakeResolver = host -> List.of(InetAddress.getByName("10.0.0.5"));
        INetworkPolicy policy =
                NetworkPolicies.builder(fakeResolver).denyPrivateAddresses().build();

        PolicyDecision decision = policy.evaluate(context("https://internal.example.com/"));
        assertThat(decision.isDeny()).isTrue();
        assertThat(decision.reason())
                .isEqualTo(
                        NetworkPolicyReasons.addressCategoryDenied(NetworkAddressCategory.PRIVATE));
    }

    @Test
    void mixedPublicAndPrivateResolutionResultsAreDenied() throws UnknownHostException {
        INetworkAddressResolver mixedResolver =
                host ->
                        List.of(
                                InetAddress.getByName("8.8.8.8"),
                                InetAddress.getByName("192.168.1.1"));
        INetworkPolicy policy =
                NetworkPolicies.builder(mixedResolver).denyPrivateAddresses().build();

        assertThat(policy.evaluate(context("https://mixed.example.com/")).isDeny()).isTrue();
    }

    @Test
    void allPublicResolutionResultsAreAllowed() throws UnknownHostException {
        INetworkAddressResolver publicResolver = host -> List.of(InetAddress.getByName("8.8.8.8"));
        INetworkPolicy policy =
                NetworkPolicies.builder(publicResolver).denyPrivateAddresses().build();

        assertThat(policy.evaluate(context("https://public.example.com/")).isAllow()).isTrue();
    }

    @Test
    void resolverExceptionFailsEvaluationRatherThanBeingSwallowedIntoAnAllow() {
        INetworkAddressResolver throwingResolver =
                host -> {
                    throw new java.net.UnknownHostException("simulated DNS failure");
                };
        INetworkPolicy policy =
                NetworkPolicies.builder(throwingResolver).denyPrivateAddresses().build();

        assertThatThrownBy(() -> policy.evaluate(context("https://broken.example.com/")))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void emptyResolutionWithRequireResolutionForHostnamesDenies() {
        INetworkAddressResolver emptyResolver = host -> List.of();
        INetworkPolicy policy =
                NetworkPolicies.builder(emptyResolver).requireResolutionForHostnames().build();

        PolicyDecision decision = policy.evaluate(context("https://noaddress.example.com/"));
        assertThat(decision.isDeny()).isTrue();
        assertThat(decision.reason())
                .isEqualTo(NetworkPolicyReasons.RESOLUTION_REQUIRED_BUT_UNRESOLVED);
    }

    @Test
    void nullResolverResultFailsEvaluation() {
        INetworkAddressResolver nullResolver = host -> null;
        INetworkPolicy policy =
                NetworkPolicies.builder(nullResolver).requireResolutionForHostnames().build();

        assertThatThrownBy(() -> policy.evaluate(context("https://null.example.com/")))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void nullEntryInResolverResultFailsEvaluation() {
        java.util.List<InetAddress> withNull = new java.util.ArrayList<>();
        withNull.add(null);
        INetworkAddressResolver nullEntryResolver = host -> withNull;
        INetworkPolicy policy =
                NetworkPolicies.builder(nullEntryResolver).denyPrivateAddresses().build();

        assertThatThrownBy(() -> policy.evaluate(context("https://nullentry.example.com/")))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void resolutionNeverHappensWhenNoAddressCategoryRuleOrRequireResolutionIsConfigured() {
        INetworkAddressResolver resolverThatMustNeverBeCalled =
                host -> {
                    throw new AssertionError(
                            "resolver must never be called when no category rule is configured");
                };
        INetworkPolicy policy =
                NetworkPolicies.builder(resolverThatMustNeverBeCalled).allowScheme("https").build();

        assertThat(policy.evaluate(context("https://hostname.example.com/")).isAllow()).isTrue();
    }

    @Test
    void resolverIsCalledAtMostOnceNoHiddenRetry() throws UnknownHostException {
        java.util.concurrent.atomic.AtomicInteger calls =
                new java.util.concurrent.atomic.AtomicInteger();
        INetworkAddressResolver countingResolver =
                host -> {
                    calls.incrementAndGet();
                    return List.of(InetAddress.getByName("8.8.8.8"));
                };
        INetworkPolicy policy =
                NetworkPolicies.builder(countingResolver).denyPrivateAddresses().build();

        policy.evaluate(context("https://counted.example.com/"));

        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void builderRejectsNullAndBlankScheme() {
        assertThatThrownBy(() -> NetworkPolicies.builder().allowScheme(null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> NetworkPolicies.builder().allowScheme(" "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void builderRejectsNullAndBlankHost() {
        assertThatThrownBy(() -> NetworkPolicies.builder().allowHost(null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> NetworkPolicies.builder().allowHost(""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void allowHostCanonicalizesUppercaseToMatchALowercaseDestination() {
        INetworkPolicy policy = NetworkPolicies.builder().allowHost("EXAMPLE.com").build();
        assertThat(policy.evaluate(context("https://example.com/")).isAllow()).isTrue();
    }

    @Test
    void allowHostCanonicalizesATrailingDotToMatchADestinationWithoutOne() {
        INetworkPolicy policy = NetworkPolicies.builder().allowHost("example.com.").build();
        assertThat(policy.evaluate(context("https://example.com/")).isAllow()).isTrue();
    }

    @Test
    void allowHostCanonicalizesAUnicodeIdnHostToMatchAPunycodeDestination() {
        INetworkPolicy policy = NetworkPolicies.builder().allowHost("münchen.example").build();
        assertThat(policy.evaluate(context("https://xn--mnchen-3ya.example/")).isAllow()).isTrue();
    }

    @Test
    void allowHostAcceptsAnAlreadyPunycodeEncodedHost() {
        INetworkPolicy policy =
                NetworkPolicies.builder().allowHost("xn--mnchen-3ya.example").build();
        assertThat(policy.evaluate(context("https://xn--mnchen-3ya.example/")).isAllow()).isTrue();
    }

    @Test
    void allowHostSubdomainBoundaryHoldsUnderCanonicalization() {
        INetworkPolicy policy =
                NetworkPolicies.builder().allowHost("EXAMPLE.com.").includeSubdomains(true).build();
        assertThat(policy.evaluate(context("https://sub.example.com/")).isAllow()).isTrue();
        assertThat(policy.evaluate(context("https://deep.sub.example.com/")).isAllow()).isTrue();
        assertThat(policy.evaluate(context("https://example.com/")).isAllow()).isTrue();
        assertThat(policy.evaluate(context("https://evil-example.com/")).isDeny()).isTrue();
    }

    @Test
    void allowHostRejectsAMalformedConfiguredHostAtConfigurationTime() {
        assertThatThrownBy(() -> NetworkPolicies.builder().allowHost("exa mple.com"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> NetworkPolicies.builder().allowHost("example.com/path"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> NetworkPolicies.builder().allowHost("user@example.com"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> NetworkPolicies.builder().allowHost("example.com:8080"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> NetworkPolicies.builder().allowHost("."))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void builderRejectsOutOfRangePorts() {
        assertThatThrownBy(() -> NetworkPolicies.builder().allowPort(0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> NetworkPolicies.builder().allowPort(65536))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void builderRejectsNullResolver() {
        assertThatThrownBy(() -> NetworkPolicies.builder(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void combinedSchemeHostAndPortAllowListsAllRequireSatisfaction() {
        INetworkPolicy policy =
                NetworkPolicies.builder()
                        .allowScheme("https")
                        .allowHost("example.com")
                        .allowPort(443)
                        .build();
        assertThat(policy.evaluate(context("https://example.com/")).isAllow()).isTrue();
        assertThat(policy.evaluate(context("https://example.com:8443/")).isDeny()).isTrue();
        assertThat(policy.evaluate(context("http://example.com/")).isDeny()).isTrue();
        assertThat(policy.evaluate(context("https://other.com/")).isDeny()).isTrue();
    }

    @Test
    void systemResolverFactoryProducesAWorkingResolverForLoopback() throws UnknownHostException {
        List<InetAddress> resolved = INetworkAddressResolver.system().resolve("localhost");
        assertThat(resolved).isNotEmpty();
    }

    private static NetworkPolicyContext context(String uri) {
        return new NetworkPolicyContext(
                NetworkRequestKind.HTTP_FETCH,
                NetworkDestination.of(URI.create(uri)),
                NetworkCheckPhase.PRE_REQUEST);
    }
}
