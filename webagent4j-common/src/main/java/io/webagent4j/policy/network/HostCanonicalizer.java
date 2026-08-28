package io.webagent4j.policy.network;

import java.net.IDN;
import java.util.Locale;
import java.util.Objects;

/**
 * Canonicalizes a hostname the one way every consumer in this package agrees on, so a request's
 * actual destination host ({@link NetworkDestination#of(java.net.URI)}) and a configured allow-list
 * host ({@link NetworkPolicies.Builder#allowHost(String)}) are always compared on identical terms:
 * lowercase ({@link Locale#ROOT}), no trailing dot, and ASCII/punycode via {@link
 * IDN#toASCII(String)} for any label that is not already ASCII. Deterministic and never performs
 * DNS resolution.
 */
final class HostCanonicalizer {

    private HostCanonicalizer() {}

    /**
     * Canonicalizes a host already known to be syntactically plausible (extracted from a {@link
     * java.net.URI} the JDK itself already parsed) - a label that cannot be converted to punycode
     * is kept as its raw lowercased form rather than failing, matching {@link NetworkDestination}'s
     * long-standing behavior of never refusing to describe a real request's destination.
     */
    static String canonicalizeLenient(String rawHost) {
        String lower = stripTrailingDotAndLowercase(requireNonBlank(rawHost));
        try {
            return IDN.toASCII(lower).toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException notConvertible) {
            return lower;
        }
    }

    /**
     * Canonicalizes a host supplied at policy-configuration time, where a malformed value is a
     * caller mistake that should fail predictably and immediately rather than silently degrading
     * into a host that can never match a real request's canonicalized destination.
     *
     * @throws IllegalArgumentException if {@code rawHost} is blank, contains characters that are
     *     never valid in a bare hostname (whitespace, {@code /}, {@code @}, {@code :}, {@code ?},
     *     {@code #}, {@code \}), or cannot be converted to ASCII/punycode
     */
    static String canonicalizeStrict(String rawHost) {
        String lower = stripTrailingDotAndLowercase(requireNonBlank(rawHost));
        validateHostnameCharacters(rawHost, lower);
        try {
            return IDN.toASCII(lower).toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException notConvertible) {
            throw new IllegalArgumentException(
                    "host is not a valid hostname: " + rawHost, notConvertible);
        }
    }

    private static String requireNonBlank(String rawHost) {
        Objects.requireNonNull(rawHost, "host");
        if (rawHost.isBlank()) {
            throw new IllegalArgumentException("host cannot be blank");
        }
        return rawHost;
    }

    private static String stripTrailingDotAndLowercase(String rawHost) {
        String withoutTrailingDot =
                rawHost.endsWith(".") ? rawHost.substring(0, rawHost.length() - 1) : rawHost;
        if (withoutTrailingDot.isEmpty()) {
            throw new IllegalArgumentException("host cannot be just a trailing dot: " + rawHost);
        }
        return withoutTrailingDot.toLowerCase(Locale.ROOT);
    }

    private static void validateHostnameCharacters(String rawHost, String lower) {
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            if (Character.isWhitespace(c)
                    || c == '/'
                    || c == '@'
                    || c == ':'
                    || c == '?'
                    || c == '#'
                    || c == '\\') {
                throw new IllegalArgumentException(
                        "host contains characters not valid in a hostname: " + rawHost);
            }
        }
    }
}
