package io.webagent4j.locator;

import io.webagent4j.dom.ElementState;
import io.webagent4j.dom.IElement;
import io.webagent4j.locator.api.LocatorDefinition;
import io.webagent4j.locator.api.TextMatch;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** Computes bounded candidate score and confidence from centralized evidence weights. */
public final class LocatorScorer {

    private final TextMatcher textMatcher;
    private final IInteractabilityChecker interactabilityChecker;

    /** Creates a scorer using the default text matcher and interactability checker. */
    public LocatorScorer() {
        this(new TextMatcher(), new io.webagent4j.locator.internal.DefaultInteractabilityChecker());
    }

    /** Creates a scorer with supplied deterministic collaborators. */
    public LocatorScorer(TextMatcher textMatcher, IInteractabilityChecker interactabilityChecker) {
        this.textMatcher = java.util.Objects.requireNonNull(textMatcher, "textMatcher");
        this.interactabilityChecker =
                java.util.Objects.requireNonNull(interactabilityChecker, "interactabilityChecker");
    }

    /** Compatibility constructor using a supplied text matcher. */
    public LocatorScorer(TextMatcher textMatcher) {
        this(textMatcher, new io.webagent4j.locator.internal.DefaultInteractabilityChecker());
    }

    /** Scores one discovered candidate or rejects a failed text criterion. */
    public ScoreDecision score(
            LocatorDefinition definition,
            LocatorPlanStep step,
            LocatorBackendCandidate candidate,
            LocatorScoringConfig config,
            double fuzzyThreshold) {
        return score(definition, step, candidate, config, fuzzyThreshold, java.util.Locale.ROOT);
    }

    /** Scores one discovered candidate using an explicit deterministic locale. */
    public ScoreDecision score(
            LocatorDefinition definition,
            LocatorPlanStep step,
            LocatorBackendCandidate candidate,
            LocatorScoringConfig config,
            double fuzzyThreshold,
            Locale locale) {
        IElement element = candidate.element();
        List<LocatorEvidence> evidence = new ArrayList<>();
        if (authoritativeAccessibleNameMismatch(
                definition, step, element, locale, fuzzyThreshold)) {
            return ScoreDecision.rejected();
        }
        double quality = textQuality(step.query(), element, locale);
        if (step.query().text().isPresent() && quality == 0.0) {
            return ScoreDecision.rejected();
        }
        boolean fuzzy = step.query().strategy() == LocatorStrategyType.FUZZY_TEXT;
        if (fuzzy && quality < fuzzyThreshold) {
            return ScoreDecision.rejected();
        }

        addDiscoveryEvidence(step.query(), element, config, quality, evidence);
        addRoleEvidence(definition, element, config, evidence);
        ElementState state = element.state();
        addStateEvidence(definition, step.query().strategy(), state, config, evidence);

        double score = clamp(evidence.stream().mapToDouble(LocatorEvidence::contribution).sum());
        boolean exact = !fuzzy;
        boolean interactable = interactabilityChecker.check(element).interactable();
        double confidence = exact ? score : Math.min(score, quality * 0.90);
        return ScoreDecision.accepted(
                new LocatorCandidate(
                        candidate.identity(),
                        element,
                        step.query().strategy(),
                        score,
                        confidence,
                        candidate.domOrder(),
                        evidence,
                        exact,
                        true,
                        interactable));
    }

    private double textQuality(LocatorBackendQuery query, IElement element, Locale locale) {
        if (query.text().isEmpty()) {
            return 1.0;
        }
        TextMatch requested = query.text().orElseThrow();
        double quality =
                textMatcher.score(requested, actualText(query.strategy(), element), locale);
        if (query.strategy() == LocatorStrategyType.FUZZY_TEXT
                && !hasAuthoritativeAccessibleName(element)) {
            quality = Math.max(quality, textMatcher.score(requested, element.text(), locale));
        }
        return quality;
    }

    private boolean authoritativeAccessibleNameMismatch(
            LocatorDefinition definition,
            LocatorPlanStep step,
            IElement element,
            Locale locale,
            double fuzzyThreshold) {
        if (definition.accessibleName().isEmpty()
                || !hasAuthoritativeAccessibleName(element)
                || (step.query().strategy() != LocatorStrategyType.LABEL
                        && step.query().strategy() != LocatorStrategyType.VISIBLE_TEXT)) {
            return false;
        }
        TextMatch requested = definition.accessibleName().orElseThrow();
        double quality = textMatcher.score(requested, element.accessibleName(), locale);
        return requested.type() == io.webagent4j.locator.api.TextMatchType.FUZZY
                ? quality < fuzzyThreshold
                : quality == 0.0;
    }

    private static boolean hasAuthoritativeAccessibleName(IElement element) {
        Map<String, String> attributes = element.attributes();
        return !attributes.getOrDefault("aria-label", "").isBlank()
                || !attributes.getOrDefault("aria-labelledby", "").isBlank();
    }

    private static void addDiscoveryEvidence(
            LocatorBackendQuery query,
            IElement element,
            LocatorScoringConfig config,
            double quality,
            List<LocatorEvidence> evidence) {
        LocatorStrategyType strategy = query.strategy();
        if (strategy == LocatorStrategyType.ROLE) {
            evidence.add(
                    new LocatorEvidence(
                            strategy,
                            LocatorMatchType.EXACT,
                            query.role().map(Enum::name).orElse("any role"),
                            element.role().name(),
                            config.roleWeight()));
            return;
        }
        double weight = baseWeight(strategy, config);
        LocatorMatchType matchType =
                strategy == LocatorStrategyType.FUZZY_TEXT
                        ? LocatorMatchType.FUZZY
                        : isAttributeStrategy(strategy)
                                ? LocatorMatchType.ATTRIBUTE
                                : LocatorMatchType.EXACT;
        String expected =
                query.text().map(TextMatch::value).orElseGet(() -> query.value().orElse(""));
        String actual =
                query.text().isPresent()
                        ? actualText(strategy, element)
                        : query.value().orElse(expected);
        evidence.add(
                new LocatorEvidence(
                        strategy, matchType, expected, actual, clamp(weight * quality)));
    }

    private static void addRoleEvidence(
            LocatorDefinition definition,
            IElement element,
            LocatorScoringConfig config,
            List<LocatorEvidence> evidence) {
        if (definition.role().isPresent()
                && evidence.stream()
                        .noneMatch(item -> item.strategy() == LocatorStrategyType.ROLE)) {
            evidence.add(
                    new LocatorEvidence(
                            LocatorStrategyType.ROLE,
                            LocatorMatchType.EXACT,
                            definition.role().orElseThrow().name(),
                            element.role().name(),
                            config.roleWeight()));
        }
    }

    private static void addStateEvidence(
            LocatorDefinition definition,
            LocatorStrategyType strategy,
            ElementState state,
            LocatorScoringConfig config,
            List<LocatorEvidence> evidence) {
        if (definition.visible().isEmpty() && state.visible()) {
            evidence.add(stateEvidence("visible", config.visibleWeight()));
        }
        if (definition.enabled().isEmpty() && state.enabled()) {
            evidence.add(stateEvidence("enabled", config.enabledWeight()));
        }
        if (state.interactabilityKnown() && state.clickable()) {
            evidence.add(stateEvidence("clickable", config.interactableWeight()));
        }
    }

    private static LocatorEvidence stateEvidence(String state, double contribution) {
        return new LocatorEvidence(
                LocatorStrategyType.ROLE, LocatorMatchType.STATE, state, "true", contribution);
    }

    private static String actualText(LocatorStrategyType strategy, IElement element) {
        Map<String, String> attributes = element.attributes();
        return switch (strategy) {
            case PLACEHOLDER -> attributes.getOrDefault("placeholder", "");
            case TITLE -> attributes.getOrDefault("title", "");
            case ALT_TEXT -> attributes.getOrDefault("alt", element.accessibleName());
            case VISIBLE_TEXT -> element.text();
            case LABEL, ACCESSIBLE_NAME, FUZZY_TEXT -> element.accessibleName();
            default -> "";
        };
    }

    private static double baseWeight(LocatorStrategyType strategy, LocatorScoringConfig config) {
        return switch (strategy) {
            case ACCESSIBLE_NAME -> config.accessibleNameWeight();
            case LABEL -> config.labelWeight();
            case VISIBLE_TEXT -> config.visibleTextWeight();
            case FUZZY_TEXT -> config.fuzzyTextWeight();
            case PLACEHOLDER, TITLE, ALT_TEXT, ID, NAME_ATTRIBUTE, ATTRIBUTE, TEST_ID ->
                    config.attributeWeight();
            case ROLE -> config.roleWeight();
            case CSS, XPATH, DOM_RELATION, CUSTOM -> 1.0;
        };
    }

    private static boolean isAttributeStrategy(LocatorStrategyType strategy) {
        return switch (strategy) {
            case PLACEHOLDER, TITLE, ALT_TEXT, ID, NAME_ATTRIBUTE, ATTRIBUTE, TEST_ID, CSS, XPATH ->
                    true;
            default -> false;
        };
    }

    private static double clamp(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    /** Scoring outcome retaining rejection without allocating a synthetic candidate. */
    public record ScoreDecision(Optional<LocatorCandidate> candidate) {

        /** Validates the optional outcome. */
        public ScoreDecision {
            candidate = java.util.Objects.requireNonNull(candidate, "candidate");
        }

        /** Creates an accepted score. */
        public static ScoreDecision accepted(LocatorCandidate candidate) {
            return new ScoreDecision(Optional.of(candidate));
        }

        /** Creates a below-threshold rejection. */
        public static ScoreDecision rejected() {
            return new ScoreDecision(Optional.empty());
        }
    }
}
