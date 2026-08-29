package io.webagent4j.policy.network;

import java.net.IDN;
import java.net.URI;
import java.util.Locale;
import java.util.Objects;

/**
 * A safe, canonicalized description of a network destination - the only view of a requested URL an
 * {@link INetworkPolicy} ever sees.
 *
 * <p>Deliberately narrow: {@link #of(URI)} never even retains a request's userinfo, query, or
 * fragment - {@link #hasUserInfo()} exposes only whether one was present, never its content - so
 * there is no field on this type a safe renderer could accidentally leak. {@link #toString()}
 * renders exactly {@code scheme://host:port}.
 *
 * @param scheme lowercase URI scheme (for example {@code "https"})
 * @param host canonicalized host: lowercased, a trailing dot removed, and converted to ASCII/
 *     punycode via {@link IDN#toASCII(String)} when it is not already ASCII (a host that fails IDN
 *     conversion is kept as its raw lowercased form rather than failing construction)
 * @param port the explicit or scheme-default port ({@code 80} for {@code http}, {@code 443} for
 *     {@code https}), or {@code -1} if the scheme has no known default and none was specified
 * @param hasUserInfo whether the original URL carried a userinfo component ({@code user:pass@})
 */
public record NetworkDestination(String scheme, String host, int port, boolean hasUserInfo) {

    /**
     * Validates that every field is present and the port is in range, and canonicalizes {@code
     * scheme} and {@code host} exactly the same way regardless of which public entry point - {@link
     * #of(URI)} or this constructor directly - was used to build this instance. Both must agree on
     * the same requested destination, so canonicalization lives here, in the one place every
     * construction path already passes through, rather than being duplicated (and risking drifting
     * out of sync) in {@link #of(URI)} alone.
     */
    public NetworkDestination {
        Objects.requireNonNull(scheme, "scheme");
        Objects.requireNonNull(host, "host");
        if (scheme.isBlank()) {
            throw new IllegalArgumentException("scheme cannot be blank");
        }
        if (host.isBlank()) {
            throw new IllegalArgumentException("host cannot be blank");
        }
        if (port != -1 && (port < 1 || port > 65535)) {
            throw new IllegalArgumentException(
                    "port must be -1 or between 1 and 65535, was " + port);
        }
        scheme = scheme.toLowerCase(Locale.ROOT);
        host = HostCanonicalizer.canonicalizeLenient(host);
    }

    /**
     * Builds a canonicalized destination from a full request URI. The URI's userinfo, query, and
     * fragment are read only to compute {@link #hasUserInfo()}; none of their content is retained.
     *
     * @throws IllegalArgumentException if {@code uri} has no host (a relative or opaque URI)
     */
    public static NetworkDestination of(URI uri) {
        Objects.requireNonNull(uri, "uri");
        String rawHost = uri.getHost();
        if (rawHost == null || rawHost.isBlank()) {
            throw new IllegalArgumentException("uri must have a host: " + safeSchemeOnly(uri));
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme();
        int port = resolvePort(uri.getPort(), scheme.toLowerCase(Locale.ROOT));
        boolean hasUserInfo = uri.getRawUserInfo() != null;
        // The canonical constructor above applies the exact same scheme/host canonicalization
        // this factory used to duplicate inline - passing the raw values through here rather than
        // pre-canonicalizing them keeps the two public creation paths structurally unable to
        // disagree, instead of merely happening to agree today.
        return new NetworkDestination(scheme, rawHost, port, hasUserInfo);
    }

    private static int resolvePort(int explicitPort, String scheme) {
        if (explicitPort != -1) {
            return explicitPort;
        }
        return switch (scheme) {
            case "http" -> 80;
            case "https" -> 443;
            default -> -1;
        };
    }

    /** Never includes userinfo, query, or fragment - only the scheme is safe to expose here. */
    private static String safeSchemeOnly(URI uri) {
        return uri.getScheme() == null ? "(no scheme)" : uri.getScheme();
    }

    /** Renders exactly {@code scheme://host:port} - always safe to log. */
    @Override
    public String toString() {
        return scheme + "://" + host + ":" + port;
    }
}
