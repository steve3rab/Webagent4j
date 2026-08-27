package io.webagent4j.policy.network;

/**
 * Classification of one resolved {@link java.net.InetAddress} against well-known special-use IPv4
 * and IPv6 address ranges. See {@link NetworkAddressClassifier} for the exact ranges each category
 * covers.
 */
public enum NetworkAddressCategory {

    /** {@code 127.0.0.0/8} or {@code ::1}. */
    LOOPBACK,

    /** {@code 10.0.0.0/8}, {@code 172.16.0.0/12}, {@code 192.168.0.0/16}, or {@code fc00::/7}. */
    PRIVATE,

    /** {@code 169.254.0.0/16} or {@code fe80::/10}. */
    LINK_LOCAL,

    /** {@code 224.0.0.0/4} or {@code ff00::/8}. */
    MULTICAST,

    /** {@code 0.0.0.0} or {@code ::}. */
    UNSPECIFIED,

    /** {@code 100.64.0.0/10} (RFC 6598 carrier-grade NAT). */
    SHARED,

    /**
     * A documentation/test-net range: {@code 192.0.2.0/24}, {@code 198.51.100.0/24}, {@code
     * 203.0.113.0/24}, or {@code 2001:db8::/32}.
     */
    DOCUMENTATION,

    /** {@code 198.18.0.0/15} (RFC 2544 benchmarking). */
    BENCHMARK,

    /**
     * Another IETF-reserved special-use range not covered by a more specific category above -
     * {@code 0.0.0.0/8} (other than {@code 0.0.0.0} itself), {@code 192.0.0.0/24}, {@code
     * 240.0.0.0/4}, {@code 255.255.255.255}, or the deprecated IPv6 site-local range {@code
     * fec0::/10}.
     */
    RESERVED,

    /** None of the above - an ordinary globally routable address, or an unrecognized range. */
    GLOBAL_OR_OTHER
}
