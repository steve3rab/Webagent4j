package io.webagent4j.policy.network;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.Objects;

/**
 * Classifies one {@link InetAddress} into a {@link NetworkAddressCategory} using only {@code
 * java.net} - no third-party CIDR library. Stateless and deterministic: the same address always
 * classifies the same way.
 */
public final class NetworkAddressClassifier {

    private NetworkAddressClassifier() {}

    /** Classifies {@code address}. An IPv4-mapped IPv6 address is classified as its IPv4 form. */
    public static NetworkAddressCategory classify(InetAddress address) {
        Objects.requireNonNull(address, "address");
        if (address instanceof Inet6Address v6) {
            byte[] bytes = v6.getAddress();
            if (isIpv4Mapped(bytes)) {
                return classifyIpv4(new byte[] {bytes[12], bytes[13], bytes[14], bytes[15]});
            }
            return classifyIpv6(bytes);
        }
        if (address instanceof Inet4Address v4) {
            return classifyIpv4(v4.getAddress());
        }
        return NetworkAddressCategory.GLOBAL_OR_OTHER;
    }

    private static boolean isIpv4Mapped(byte[] bytes) {
        for (int i = 0; i < 10; i++) {
            if (bytes[i] != 0) {
                return false;
            }
        }
        return (bytes[10] & 0xFF) == 0xFF && (bytes[11] & 0xFF) == 0xFF;
    }

    private static int unsigned(byte b) {
        return b & 0xFF;
    }

    private static NetworkAddressCategory classifyIpv4(byte[] b) {
        int a0 = unsigned(b[0]);
        int a1 = unsigned(b[1]);

        if (a0 == 0) {
            boolean allZero = a1 == 0 && unsigned(b[2]) == 0 && unsigned(b[3]) == 0;
            return allZero ? NetworkAddressCategory.UNSPECIFIED : NetworkAddressCategory.RESERVED;
        }
        if (a0 == 10) {
            return NetworkAddressCategory.PRIVATE;
        }
        if (a0 == 100 && a1 >= 64 && a1 <= 127) {
            return NetworkAddressCategory.SHARED;
        }
        if (a0 == 127) {
            return NetworkAddressCategory.LOOPBACK;
        }
        if (a0 == 169 && a1 == 254) {
            return NetworkAddressCategory.LINK_LOCAL;
        }
        if (a0 == 172 && a1 >= 16 && a1 <= 31) {
            return NetworkAddressCategory.PRIVATE;
        }
        if (a0 == 192 && a1 == 0 && unsigned(b[2]) == 0) {
            return NetworkAddressCategory.RESERVED;
        }
        if (a0 == 192 && a1 == 0 && unsigned(b[2]) == 2) {
            return NetworkAddressCategory.DOCUMENTATION;
        }
        if (a0 == 192 && a1 == 168) {
            return NetworkAddressCategory.PRIVATE;
        }
        if (a0 == 198 && (a1 == 18 || a1 == 19)) {
            return NetworkAddressCategory.BENCHMARK;
        }
        if (a0 == 198 && a1 == 51 && unsigned(b[2]) == 100) {
            return NetworkAddressCategory.DOCUMENTATION;
        }
        if (a0 == 203 && a1 == 0 && unsigned(b[2]) == 113) {
            return NetworkAddressCategory.DOCUMENTATION;
        }
        if (a0 >= 224 && a0 <= 239) {
            return NetworkAddressCategory.MULTICAST;
        }
        if (a0 >= 240) {
            // Covers 240.0.0.0/4 (reserved) and 255.255.255.255 (limited broadcast) alike - this
            // taxonomy has no separate BROADCAST category, so the single limited-broadcast address
            // is reported as RESERVED, consistent with it being just as unroutable on the public
            // internet as the rest of this range.
            return NetworkAddressCategory.RESERVED;
        }
        return NetworkAddressCategory.GLOBAL_OR_OTHER;
    }

    private static NetworkAddressCategory classifyIpv6(byte[] b) {
        if (isAllZero(b)) {
            return NetworkAddressCategory.UNSPECIFIED;
        }
        if (isLoopback(b)) {
            return NetworkAddressCategory.LOOPBACK;
        }
        int first = unsigned(b[0]);
        if ((first & 0xFE) == 0xFC) {
            // fc00::/7
            return NetworkAddressCategory.PRIVATE;
        }
        if (first == 0xFE && (unsigned(b[1]) & 0xC0) == 0x80) {
            // fe80::/10
            return NetworkAddressCategory.LINK_LOCAL;
        }
        if (first == 0xFE && (unsigned(b[1]) & 0xC0) == 0xC0) {
            // fec0::/10 - deprecated site-local
            return NetworkAddressCategory.RESERVED;
        }
        if (first == 0xFF) {
            // ff00::/8
            return NetworkAddressCategory.MULTICAST;
        }
        if (matchesPrefix(b, new int[] {0x20, 0x01, 0x0D, 0xB8})) {
            // 2001:db8::/32
            return NetworkAddressCategory.DOCUMENTATION;
        }
        return NetworkAddressCategory.GLOBAL_OR_OTHER;
    }

    private static boolean isAllZero(byte[] b) {
        for (byte value : b) {
            if (value != 0) {
                return false;
            }
        }
        return true;
    }

    private static boolean isLoopback(byte[] b) {
        for (int i = 0; i < 15; i++) {
            if (b[i] != 0) {
                return false;
            }
        }
        return b[15] == 1;
    }

    private static boolean matchesPrefix(byte[] b, int[] expectedBytes) {
        for (int i = 0; i < expectedBytes.length; i++) {
            if (unsigned(b[i]) != expectedBytes[i]) {
                return false;
            }
        }
        return true;
    }
}
