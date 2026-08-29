package io.webagent4j.browsercrawler.internal;

import io.webagent4j.crawler.api.IUrlNormalizer;
import io.webagent4j.crawler.api.QueryParameterPolicy;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * The dedup-identity normalizer for browser-discovered URLs: lowercases scheme/host, drops the
 * fragment, drops the default port, resolves {@code .}/{@code ..} segments, maps an empty path to
 * {@code /}, and applies the configured {@link QueryParameterPolicy}.
 *
 * <p>{@code webagent4j-crawler}'s {@code DefaultUrlNormalizer} implements identical rules, but
 * lives in the HTTP crawler engine module (which also pulls in jsoup and the HTTP fetcher) -
 * depending on that whole module here just to reuse forty lines of pure-URI logic would be a
 * heavier, more awkward coupling than this small, self-contained duplicate. Both implement the same
 * {@code IUrlNormalizer} contract from {@code webagent4j-crawler-api} (which this module already
 * lightly depends on) and are interchangeable for a caller supplying their own.
 */
public final class BrowserUrlNormalizer implements IUrlNormalizer {

    private final QueryParameterPolicy queryParameterPolicy;

    public BrowserUrlNormalizer(QueryParameterPolicy queryParameterPolicy) {
        this.queryParameterPolicy =
                Objects.requireNonNull(queryParameterPolicy, "queryParameterPolicy");
    }

    @Override
    public URI normalize(URI uri) {
        if (!uri.isAbsolute() || uri.getHost() == null) {
            throw new IllegalArgumentException(
                    "uri must be absolute with a host: " + safeDescription(uri));
        }
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        String portSuffix = portSuffix(scheme, uri.getPort());
        String path = uri.getPath();
        if (path == null || path.isEmpty()) {
            path = "/";
        }
        String query = filteredQuery(uri.getRawQuery());
        String rebuilt = scheme + "://" + host + portSuffix + path + query;
        return URI.create(rebuilt).normalize();
    }

    /**
     * Renders {@code scheme://host} only - never userinfo, path, query, or fragment, any of which
     * could carry credentials or another sensitive token embedded in the URL a caller is trying to
     * normalize.
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
        boolean defaultHttp = scheme.equals("http") && port == 80;
        boolean defaultHttps = scheme.equals("https") && port == 443;
        return (defaultHttp || defaultHttps) ? "" : ":" + port;
    }

    private String filteredQuery(String rawQuery) {
        if (rawQuery == null || rawQuery.isEmpty()) {
            return "";
        }
        String kept =
                java.util.Arrays.stream(rawQuery.split("&"))
                        .filter(pair -> !pair.isEmpty())
                        .filter(
                                pair ->
                                        queryParameterPolicy.keeps(
                                                decodeBestEffort(paramName(pair))))
                        .collect(Collectors.joining("&"));
        return kept.isEmpty() ? "" : "?" + kept;
    }

    private static String paramName(String pair) {
        int equalsIndex = pair.indexOf('=');
        return equalsIndex >= 0 ? pair.substring(0, equalsIndex) : pair;
    }

    private static String decodeBestEffort(String value) {
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException notEncoded) {
            return value;
        }
    }
}
