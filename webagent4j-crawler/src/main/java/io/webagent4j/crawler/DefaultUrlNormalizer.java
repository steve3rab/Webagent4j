package io.webagent4j.crawler;

import io.webagent4j.crawler.api.IUrlNormalizer;
import io.webagent4j.crawler.api.QueryParameterPolicy;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Deterministic {@link IUrlNormalizer}: lowercases scheme and host, drops the fragment, drops a
 * default port ({@code :80} for {@code http}, {@code :443} for {@code https}), resolves {@code .}/
 * {@code ..} path segments ({@link URI#normalize()}), maps an empty path to {@code "/"}, and
 * applies a fixed {@link QueryParameterPolicy} - never reordering surviving query parameters or
 * re-encoding anything, so already-valid percent-encoding is never corrupted.
 *
 * <p>A trailing slash on a non-empty path is left exactly as given: {@code /products} and {@code
 * /products/} are not collapsed into each other, since many servers treat them as different
 * resources - only the empty path is normalized, to {@code /}, per RFC 3986's own equivalence rule.
 *
 * <p>{@code normalize(normalize(uri))} always equals {@code normalize(uri)}: every step is either
 * already-idempotent ({@link URI#normalize()}) or applies the exact same deterministic rule to
 * already-canonical input.
 */
public final class DefaultUrlNormalizer implements IUrlNormalizer {

    private final QueryParameterPolicy queryParameterPolicy;

    /** Creates a normalizer applying {@code queryParameterPolicy} to every URL it normalizes. */
    public DefaultUrlNormalizer(QueryParameterPolicy queryParameterPolicy) {
        this.queryParameterPolicy =
                Objects.requireNonNull(queryParameterPolicy, "queryParameterPolicy");
    }

    @Override
    public URI normalize(URI uri) {
        Objects.requireNonNull(uri, "uri");
        if (!uri.isAbsolute() || uri.getHost() == null) {
            throw new IllegalArgumentException(
                    "uri must be absolute with a host: " + safeDescription(uri));
        }
        URI resolved = uri.normalize();
        String scheme = resolved.getScheme().toLowerCase(Locale.ROOT);
        String host = resolved.getHost().toLowerCase(Locale.ROOT);
        String userInfo = resolved.getRawUserInfo();
        String portPart = portSuffix(scheme, resolved.getPort());
        String rawPath = resolved.getRawPath();
        String path = (rawPath == null || rawPath.isEmpty()) ? "/" : rawPath;
        String query = filteredQuery(resolved.getRawQuery());

        StringBuilder normalized = new StringBuilder();
        normalized.append(scheme).append("://");
        if (userInfo != null) {
            normalized.append(userInfo).append('@');
        }
        normalized.append(host).append(portPart).append(path);
        if (!query.isEmpty()) {
            normalized.append('?').append(query);
        }
        try {
            return new URI(normalized.toString());
        } catch (URISyntaxException malformed) {
            throw new IllegalArgumentException(
                    "normalization produced an invalid URI for " + safeDescription(uri), malformed);
        }
    }

    /**
     * Renders {@code scheme://host} only - never userinfo, path, query, or fragment, any of which
     * could carry credentials or another sensitive token embedded in the URL a caller is trying to
     * normalize. Used only for exception messages; {@link IUrlNormalizer} callers that need the
     * full URI already have it as a structured value (the input to this method) rather than needing
     * to parse it back out of free text.
     */
    private static String safeDescription(URI uri) {
        String scheme = uri.getScheme() == null ? "(no scheme)" : uri.getScheme();
        String host = uri.getHost() == null ? "(no host)" : uri.getHost();
        return scheme + "://" + host;
    }

    private static String portSuffix(String scheme, int port) {
        if (port == -1) {
            return "";
        }
        boolean defaultPort =
                (scheme.equals("http") && port == 80) || (scheme.equals("https") && port == 443);
        return defaultPort ? "" : ":" + port;
    }

    private String filteredQuery(String rawQuery) {
        if (rawQuery == null || rawQuery.isEmpty()) {
            return "";
        }
        List<String> kept = new ArrayList<>();
        for (String pair : rawQuery.split("&", -1)) {
            if (pair.isEmpty()) {
                continue;
            }
            String rawName = pair.contains("=") ? pair.substring(0, pair.indexOf('=')) : pair;
            if (queryParameterPolicy.keeps(decodeBestEffort(rawName))) {
                kept.add(pair);
            }
        }
        return String.join("&", kept);
    }

    private static String decodeBestEffort(String rawName) {
        try {
            return java.net.URLDecoder.decode(rawName, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException malformed) {
            return rawName;
        }
    }
}
