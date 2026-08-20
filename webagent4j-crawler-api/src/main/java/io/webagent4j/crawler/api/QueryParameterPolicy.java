package io.webagent4j.crawler.api;

import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Deterministic policy for which URL query parameters survive normalization. Never a probabilistic
 * or SEO-heuristic decision: only an explicit mode and two small, caller-controlled name sets.
 *
 * @param mode which parameters are kept by default
 * @param extraDropParameters additional parameter names (case-insensitive) to drop regardless of
 *     {@code mode}
 * @param keepParameters parameter names (case-insensitive) to always keep regardless of {@code
 *     mode}; takes precedence over {@code extraDropParameters}
 */
public record QueryParameterPolicy(
        QueryParameterMode mode, Set<String> extraDropParameters, Set<String> keepParameters) {

    /**
     * Conservative, explicitly enumerated tracking parameters dropped by {@link
     * QueryParameterMode#DROP_KNOWN_TRACKING} - never an arbitrary or expanding heuristic list.
     */
    public static final Set<String> KNOWN_TRACKING_PARAMETERS =
            Set.of("utm_source", "utm_medium", "utm_campaign", "utm_term", "utm_content");

    /** Defensively copies and lowercases both parameter sets. */
    public QueryParameterPolicy {
        Objects.requireNonNull(mode, "mode");
        extraDropParameters = lowercased(extraDropParameters);
        keepParameters = lowercased(keepParameters);
    }

    /** Keeps every query parameter unchanged. */
    public static QueryParameterPolicy keepAll() {
        return new QueryParameterPolicy(QueryParameterMode.KEEP_ALL, Set.of(), Set.of());
    }

    /** Drops every query parameter. */
    public static QueryParameterPolicy dropAll() {
        return new QueryParameterPolicy(QueryParameterMode.DROP_ALL, Set.of(), Set.of());
    }

    /** Drops only {@link #KNOWN_TRACKING_PARAMETERS}. */
    public static QueryParameterPolicy dropKnownTracking() {
        return new QueryParameterPolicy(QueryParameterMode.DROP_KNOWN_TRACKING, Set.of(), Set.of());
    }

    /** Returns a copy that additionally drops {@code parameterName}. */
    public QueryParameterPolicy excludeParameter(String parameterName) {
        Objects.requireNonNull(parameterName, "parameterName");
        return new QueryParameterPolicy(
                mode, union(extraDropParameters, parameterName), keepParameters);
    }

    /** Returns a copy that always keeps {@code parameterName}. */
    public QueryParameterPolicy includeParameter(String parameterName) {
        Objects.requireNonNull(parameterName, "parameterName");
        return new QueryParameterPolicy(
                mode, extraDropParameters, union(keepParameters, parameterName));
    }

    /** Returns whether {@code parameterName} should be kept under this policy. */
    public boolean keeps(String parameterName) {
        String lower = parameterName.toLowerCase(Locale.ROOT);
        if (keepParameters.contains(lower)) {
            return true;
        }
        if (extraDropParameters.contains(lower)) {
            return false;
        }
        return switch (mode) {
            case KEEP_ALL -> true;
            case DROP_ALL -> false;
            case DROP_KNOWN_TRACKING -> !KNOWN_TRACKING_PARAMETERS.contains(lower);
        };
    }

    private static Set<String> lowercased(Set<String> values) {
        return Objects.requireNonNull(values, "values").stream()
                .map(value -> value.toLowerCase(Locale.ROOT))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private static Set<String> union(Set<String> values, String extra) {
        java.util.Set<String> next = new java.util.HashSet<>(values);
        next.add(extra.toLowerCase(Locale.ROOT));
        return Set.copyOf(next);
    }

    /** The base rule {@link QueryParameterPolicy} applies before per-parameter overrides. */
    public enum QueryParameterMode {
        /** Every query parameter is kept. */
        KEEP_ALL,
        /** Every query parameter is dropped. */
        DROP_ALL,
        /** Only {@link #KNOWN_TRACKING_PARAMETERS} are dropped. */
        DROP_KNOWN_TRACKING
    }
}
