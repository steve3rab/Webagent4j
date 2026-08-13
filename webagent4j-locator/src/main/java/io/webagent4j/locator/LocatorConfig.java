package io.webagent4j.locator;

import java.time.Duration;
import java.util.Locale;
import java.util.Objects;

/**
 * Immutable locator-engine configuration. Defaults select balanced exact-first resolution, bounded
 * diagnostics and conservative fuzzy matching.
 *
 * @param resolutionPolicy strictness and fallback policy
 * @param fuzzyThreshold minimum accepted fuzzy similarity
 * @param ambiguityMargin maximum score difference still considered ambiguous
 * @param earlyStopConfidence confidence required for exact unique early termination
 * @param resolutionBudget global work budget
 * @param diagnosticsLevel retained diagnostics detail
 * @param locale deterministic text locale; {@link Locale#ROOT} is the default
 * @param testIdAttribute configured test-id attribute
 * @param pollingInterval interval used by non-blocking resolution polling
 * @param scoring centralized scoring weights
 */
public record LocatorConfig(
        LocatorResolutionPolicy resolutionPolicy,
        double fuzzyThreshold,
        double ambiguityMargin,
        double earlyStopConfidence,
        LocatorResolutionBudget resolutionBudget,
        LocatorDiagnosticsLevel diagnosticsLevel,
        Locale locale,
        String testIdAttribute,
        Duration pollingInterval,
        LocatorScoringConfig scoring) {

    /** Validates every immutable configuration value. */
    public LocatorConfig {
        Objects.requireNonNull(resolutionPolicy, "resolutionPolicy");
        validateUnit(fuzzyThreshold, "fuzzyThreshold");
        validateUnit(ambiguityMargin, "ambiguityMargin");
        validateUnit(earlyStopConfidence, "earlyStopConfidence");
        Objects.requireNonNull(resolutionBudget, "resolutionBudget");
        Objects.requireNonNull(diagnosticsLevel, "diagnosticsLevel");
        Objects.requireNonNull(locale, "locale");
        testIdAttribute = requireValue(testIdAttribute, "testIdAttribute");
        Objects.requireNonNull(pollingInterval, "pollingInterval");
        if (pollingInterval.isZero() || pollingInterval.isNegative()) {
            throw new IllegalArgumentException("pollingInterval must be positive");
        }
        Objects.requireNonNull(scoring, "scoring");
        if (resolutionPolicy == LocatorResolutionPolicy.PERMISSIVE
                && diagnosticsLevel != LocatorDiagnosticsLevel.DETAILED) {
            diagnosticsLevel = LocatorDiagnosticsLevel.DETAILED;
        }
    }

    /** Compatibility constructor for the original Phase 2 configuration contract. */
    @SuppressWarnings("checkstyle:ParameterNumber")
    public LocatorConfig(
            double fuzzyThreshold,
            int maximumCandidates,
            Duration defaultTimeout,
            boolean allowFuzzyMatching,
            boolean diagnosticsEnabled,
            double ambiguityTolerance,
            LocatorScoringConfig scoring) {
        this(
                allowFuzzyMatching
                        ? LocatorResolutionPolicy.BALANCED
                        : LocatorResolutionPolicy.STRICT,
                fuzzyThreshold,
                ambiguityTolerance,
                0.95,
                new LocatorResolutionBudget(defaultTimeout, maximumCandidates, 10, 50),
                diagnosticsEnabled ? LocatorDiagnosticsLevel.BASIC : LocatorDiagnosticsLevel.OFF,
                Locale.ROOT,
                "data-testid",
                Duration.ofMillis(25),
                scoring);
    }

    /** Returns balanced defaults using a five-second timeout. */
    public static LocatorConfig defaults() {
        return builder().build();
    }

    /** Returns balanced defaults using the supplied browser locator timeout. */
    public static LocatorConfig defaults(Duration timeout) {
        return builder().resolutionBudget(LocatorResolutionBudget.defaults(timeout)).build();
    }

    /** Starts an immutable configuration builder. */
    public static Builder builder() {
        return new Builder();
    }

    /** Returns whether fuzzy fallback is permitted. */
    public boolean allowFuzzyMatching() {
        return resolutionPolicy != LocatorResolutionPolicy.STRICT;
    }

    /** Returns the configured maximum candidate count. */
    public int maximumCandidates() {
        return resolutionBudget.maxCandidates();
    }

    /** Returns the configured default timeout. */
    public Duration defaultTimeout() {
        return resolutionBudget.timeout();
    }

    /** Returns whether any diagnostics are retained. */
    public boolean diagnosticsEnabled() {
        return diagnosticsLevel != LocatorDiagnosticsLevel.OFF;
    }

    /** Compatibility alias for {@link #ambiguityMargin()}. */
    public double ambiguityTolerance() {
        return ambiguityMargin;
    }

    /** Mutable construction helper that always produces an immutable configuration. */
    public static final class Builder {

        private LocatorResolutionPolicy policy = LocatorResolutionPolicy.BALANCED;
        private Double fuzzyThreshold;
        private double ambiguityMargin = 0.02;
        private double earlyStopConfidence = 0.95;
        private LocatorResolutionBudget budget = LocatorResolutionBudget.defaults();
        private LocatorDiagnosticsLevel diagnosticsLevel = LocatorDiagnosticsLevel.BASIC;
        private Locale locale = Locale.ROOT;
        private String testIdAttribute = "data-testid";
        private Duration pollingInterval = Duration.ofMillis(25);
        private LocatorScoringConfig scoring = LocatorScoringConfig.defaults();

        private Builder() {}

        /** Selects a resolution policy. */
        public Builder resolutionPolicy(LocatorResolutionPolicy value) {
            policy = Objects.requireNonNull(value, "resolutionPolicy");
            return this;
        }

        /** Overrides the policy's default fuzzy threshold. */
        public Builder fuzzyThreshold(double value) {
            fuzzyThreshold = value;
            return this;
        }

        /** Sets the ambiguity margin. */
        public Builder ambiguityMargin(double value) {
            ambiguityMargin = value;
            return this;
        }

        /** Sets the exact unique early-stop confidence. */
        public Builder earlyStopConfidence(double value) {
            earlyStopConfidence = value;
            return this;
        }

        /** Sets the global resolution budget. */
        public Builder resolutionBudget(LocatorResolutionBudget value) {
            budget = Objects.requireNonNull(value, "resolutionBudget");
            return this;
        }

        /** Sets diagnostic retention. */
        public Builder diagnosticsLevel(LocatorDiagnosticsLevel value) {
            diagnosticsLevel = Objects.requireNonNull(value, "diagnosticsLevel");
            return this;
        }

        /** Sets the deterministic text-comparison locale. */
        public Builder locale(Locale value) {
            locale = Objects.requireNonNull(value, "locale");
            return this;
        }

        /** Sets the explicit test-id attribute name. */
        public Builder testIdAttribute(String value) {
            testIdAttribute = requireValue(value, "testIdAttribute");
            return this;
        }

        /** Sets the polling interval used for dynamic DOM and stability checks. */
        public Builder pollingInterval(Duration value) {
            pollingInterval = Objects.requireNonNull(value, "pollingInterval");
            return this;
        }

        /** Sets centralized scoring weights. */
        public Builder scoring(LocatorScoringConfig value) {
            scoring = Objects.requireNonNull(value, "scoring");
            return this;
        }

        /** Builds an immutable configuration with policy-specific defaults. */
        public LocatorConfig build() {
            double threshold =
                    fuzzyThreshold == null
                            ? switch (policy) {
                                case STRICT -> 1.0;
                                case BALANCED -> 0.80;
                                case PERMISSIVE -> 0.70;
                            }
                            : fuzzyThreshold;
            return new LocatorConfig(
                    policy,
                    threshold,
                    ambiguityMargin,
                    earlyStopConfidence,
                    budget,
                    diagnosticsLevel,
                    locale,
                    testIdAttribute,
                    pollingInterval,
                    scoring);
        }
    }

    private static void validateUnit(double value, String name) {
        if (value < 0.0 || value > 1.0) {
            throw new IllegalArgumentException(name + " must be between zero and one");
        }
    }

    private static String requireValue(String value, String name) {
        Objects.requireNonNull(value, name);
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(name + " cannot be blank");
        }
        return normalized;
    }
}
