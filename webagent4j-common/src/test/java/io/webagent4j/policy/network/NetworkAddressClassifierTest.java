package io.webagent4j.policy.network;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetAddress;
import java.net.UnknownHostException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Exhaustive classification matrix for every IPv4/IPv6 special-use range this taxonomy documents.
 * Every literal address is resolved via {@link InetAddress#getByName(String)}, which never performs
 * DNS for a syntactically valid numeric address - so this test, like the classifier itself, never
 * touches the network.
 */
class NetworkAddressClassifierTest {

    @ParameterizedTest
    @CsvSource({
        "0.0.0.0, UNSPECIFIED",
        "0.0.0.1, RESERVED",
        "10.0.0.1, PRIVATE",
        "10.255.255.255, PRIVATE",
        "100.64.0.1, SHARED",
        "100.127.255.255, SHARED",
        "127.0.0.1, LOOPBACK",
        "127.255.255.255, LOOPBACK",
        "169.254.0.1, LINK_LOCAL",
        "172.16.0.1, PRIVATE",
        "172.31.255.255, PRIVATE",
        "192.0.0.1, RESERVED",
        "192.0.2.1, DOCUMENTATION",
        "192.168.0.1, PRIVATE",
        "192.168.255.255, PRIVATE",
        "198.18.0.1, BENCHMARK",
        "198.19.255.255, BENCHMARK",
        "198.51.100.1, DOCUMENTATION",
        "203.0.113.1, DOCUMENTATION",
        "224.0.0.1, MULTICAST",
        "239.255.255.255, MULTICAST",
        "240.0.0.1, RESERVED",
        "255.255.255.255, RESERVED",
        "8.8.8.8, GLOBAL_OR_OTHER",
        "1.1.1.1, GLOBAL_OR_OTHER"
    })
    void classifiesIpv4Address(String literal, NetworkAddressCategory expected) throws Exception {
        assertThat(NetworkAddressClassifier.classify(InetAddress.getByName(literal)))
                .isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource({
        "::, UNSPECIFIED",
        "::1, LOOPBACK",
        "fc00::1, PRIVATE",
        "fd00::1, PRIVATE",
        "fe80::1, LINK_LOCAL",
        "fec0::1, RESERVED",
        "ff02::1, MULTICAST",
        "2001:db8::1, DOCUMENTATION",
        "2606:4700:4700::1111, GLOBAL_OR_OTHER"
    })
    void classifiesIpv6Address(String literal, NetworkAddressCategory expected) throws Exception {
        assertThat(NetworkAddressClassifier.classify(InetAddress.getByName(literal)))
                .isEqualTo(expected);
    }

    @Test
    void classifiesIpv4MappedIpv6AddressAsItsIpv4Form() throws UnknownHostException {
        InetAddress mappedLoopback = InetAddress.getByName("::ffff:127.0.0.1");
        assertThat(NetworkAddressClassifier.classify(mappedLoopback))
                .isEqualTo(NetworkAddressCategory.LOOPBACK);

        InetAddress mappedPrivate = InetAddress.getByName("::ffff:10.0.0.1");
        assertThat(NetworkAddressClassifier.classify(mappedPrivate))
                .isEqualTo(NetworkAddressCategory.PRIVATE);
    }

    @Test
    void localhostLiteralClassifiesAsLoopbackWithoutAnyDnsLookup() throws UnknownHostException {
        // "127.0.0.1" is parsed as a numeric literal by InetAddress.getByName - never resolved.
        assertThat(NetworkAddressClassifier.classify(InetAddress.getByName("127.0.0.1")))
                .isEqualTo(NetworkAddressCategory.LOOPBACK);
    }

    @Test
    void rejectsNullAddress() {
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> NetworkAddressClassifier.classify(null))
                .isInstanceOf(NullPointerException.class);
    }
}
