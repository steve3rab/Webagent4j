package io.webagent4j.policy.network;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import org.junit.jupiter.api.Test;

class NetworkDestinationTest {

    @Test
    void canonicalizesSchemeAndHostToLowercase() {
        NetworkDestination destination =
                NetworkDestination.of(URI.create("HTTPS://Example.COM/path"));
        assertThat(destination.scheme()).isEqualTo("https");
        assertThat(destination.host()).isEqualTo("example.com");
    }

    @Test
    void resolvesDefaultPortsForHttpAndHttps() {
        assertThat(NetworkDestination.of(URI.create("http://example.com/")).port()).isEqualTo(80);
        assertThat(NetworkDestination.of(URI.create("https://example.com/")).port()).isEqualTo(443);
    }

    @Test
    void preservesExplicitPort() {
        assertThat(NetworkDestination.of(URI.create("https://example.com:8443/")).port())
                .isEqualTo(8443);
    }

    @Test
    void stripsTrailingDotFromHost() {
        assertThat(NetworkDestination.of(URI.create("https://example.com./")).host())
                .isEqualTo("example.com");
    }

    @Test
    void convertsInternationalHostToPunycode() {
        NetworkDestination destination =
                NetworkDestination.of(URI.create("https://xn--nxasmq6b.com/"));
        assertThat(destination.host()).isEqualTo("xn--nxasmq6b.com");
    }

    @Test
    void rejectsUriWithoutHost() {
        assertThatThrownBy(() -> NetworkDestination.of(URI.create("mailto:someone@example.com")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void detectsUserInfoPresenceWithoutRetainingItsContent() {
        String secretMarker = "WEBAGENT4J_URI_USERINFO_SECRET";
        NetworkDestination destination =
                NetworkDestination.of(URI.create("https://user:" + secretMarker + "@example.com/"));
        assertThat(destination.hasUserInfo()).isTrue();
        assertThat(destination.toString()).doesNotContain(secretMarker).doesNotContain("user:");
    }

    @Test
    void reportsNoUserInfoWhenAbsent() {
        assertThat(NetworkDestination.of(URI.create("https://example.com/")).hasUserInfo())
                .isFalse();
    }

    @Test
    void toStringNeverContainsQueryOrFragmentSecrets() {
        String tokenMarker = "WEBAGENT4J_URI_QUERY_SECRET";
        String fragmentMarker = "WEBAGENT4J_URI_FRAGMENT_SECRET";
        NetworkDestination destination =
                NetworkDestination.of(
                        URI.create(
                                "https://example.com/path?token="
                                        + tokenMarker
                                        + "#"
                                        + fragmentMarker));
        String rendered = destination.toString();
        assertThat(rendered).doesNotContain(tokenMarker).doesNotContain(fragmentMarker);
        assertThat(rendered).isEqualTo("https://example.com:443");
    }

    @Test
    void subdomainIsNeverConfusedWithASuffixLookalike() {
        NetworkDestination evil = NetworkDestination.of(URI.create("https://evil-example.com/"));
        NetworkDestination real = NetworkDestination.of(URI.create("https://sub.example.com/"));
        assertThat(evil.host()).isNotEqualTo("example.com");
        assertThat(evil.host().endsWith(".example.com")).isFalse();
        assertThat(real.host().endsWith(".example.com")).isTrue();
    }

    @Test
    void rejectsBlankFieldsInCanonicalConstructor() {
        assertThatThrownBy(() -> new NetworkDestination("", "host", 80, false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new NetworkDestination("https", "", 80, false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsOutOfRangePorts() {
        assertThatThrownBy(() -> new NetworkDestination("https", "example.com", 0, false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new NetworkDestination("https", "example.com", 65536, false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void negativeOneIsAllowedAsAnUnknownPortMarker() {
        assertThat(new NetworkDestination("customscheme", "example.com", -1, false).port())
                .isEqualTo(-1);
    }
}
