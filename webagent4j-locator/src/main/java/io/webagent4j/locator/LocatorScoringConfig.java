package io.webagent4j.locator;

/**
 * Centralized deterministic scoring weights. Strategies report evidence but never own weights.
 * Contributions are accumulated and clamped to the inclusive range {@code [0.0, 1.0]}.
 *
 * @param roleWeight exact semantic-role contribution
 * @param accessibleNameWeight exact accessible-name contribution
 * @param labelWeight exact associated-label contribution
 * @param visibleTextWeight exact visible-text contribution
 * @param attributeWeight exact explicit attribute contribution
 * @param fuzzyTextWeight maximum fuzzy contribution before similarity is applied
 * @param visibleWeight visible preference contribution when visibility is not constrained
 * @param enabledWeight enabled preference contribution when enabled state is not constrained
 * @param interactableWeight reliable interactability preference contribution
 */
public record LocatorScoringConfig(
        double roleWeight,
        double accessibleNameWeight,
        double labelWeight,
        double visibleTextWeight,
        double attributeWeight,
        double fuzzyTextWeight,
        double visibleWeight,
        double enabledWeight,
        double interactableWeight) {

    /** Validates all weights. */
    public LocatorScoringConfig {
        validate(roleWeight, "roleWeight");
        validate(accessibleNameWeight, "accessibleNameWeight");
        validate(labelWeight, "labelWeight");
        validate(visibleTextWeight, "visibleTextWeight");
        validate(attributeWeight, "attributeWeight");
        validate(fuzzyTextWeight, "fuzzyTextWeight");
        validate(visibleWeight, "visibleWeight");
        validate(enabledWeight, "enabledWeight");
        validate(interactableWeight, "interactableWeight");
    }

    /** Compatibility constructor for the original six semantic weights. */
    public LocatorScoringConfig(
            double roleWeight,
            double accessibleNameWeight,
            double labelWeight,
            double visibleTextWeight,
            double attributeWeight,
            double fuzzyTextWeight) {
        this(
                roleWeight,
                accessibleNameWeight,
                labelWeight,
                visibleTextWeight,
                attributeWeight,
                fuzzyTextWeight,
                0.10,
                0.10,
                0.10);
    }

    /** Returns defaults that rank exact semantic evidence ahead of fuzzy similarity. */
    public static LocatorScoringConfig defaults() {
        return new LocatorScoringConfig(0.30, 0.40, 0.20, 0.15, 0.70, 0.45, 0.10, 0.10, 0.10);
    }

    private static void validate(double value, String name) {
        if (!Double.isFinite(value) || value < 0.0 || value > 1.0) {
            throw new IllegalArgumentException(name + " must be between zero and one");
        }
    }
}
