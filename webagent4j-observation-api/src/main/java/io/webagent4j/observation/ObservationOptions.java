package io.webagent4j.observation;

import java.time.Duration;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/** Immutable user configuration for a passive bounded observation. */
public record ObservationOptions(
        ObservationMode mode,
        boolean includeHidden,
        boolean includeInputValues,
        ObservationBudget budget,
        Set<String> allowedDataAttributes) {

    /** Validates and defensively stores options. */
    public ObservationOptions {
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(budget, "budget");
        LinkedHashSet<String> attributes = new LinkedHashSet<>();
        Objects.requireNonNull(allowedDataAttributes, "allowedDataAttributes")
                .forEach(value -> attributes.add(requireDataAttribute(value)));
        allowedDataAttributes = Set.copyOf(attributes);
    }

    /** Returns standard secure defaults. */
    public static ObservationOptions defaults() {
        return builder().build();
    }

    /** Starts an immutable options builder. */
    public static Builder builder() {
        return new Builder();
    }

    /** Mutable construction helper producing immutable options. */
    public static final class Builder {
        private ObservationMode mode = ObservationMode.STANDARD;
        private boolean includeHidden;
        private boolean includeInputValues;
        private ObservationBudget budget = ObservationBudget.defaults();
        private Set<String> allowedDataAttributes = Set.of("data-testid");

        private Builder() {}

        /** Sets the semantic detail preset. */
        public Builder mode(ObservationMode value) {
            mode = Objects.requireNonNull(value, "mode");
            return this;
        }

        /** Includes explicitly marked hidden semantic elements. */
        public Builder includeHidden(boolean value) {
            includeHidden = value;
            return this;
        }

        /** Allows non-sensitive form values to be collected. */
        public Builder includeInputValues(boolean value) {
            includeInputValues = value;
            return this;
        }

        /** Replaces the global observation budget. */
        public Builder budget(ObservationBudget value) {
            budget = Objects.requireNonNull(value, "budget");
            return this;
        }

        /** Convenience override for the global timeout. */
        public Builder timeout(Duration value) {
            Objects.requireNonNull(value, "timeout");
            budget =
                    copyBudget(
                            value, budget.maxElements(), budget.maxDepth(), budget.maxTextLength());
            return this;
        }

        /** Convenience override for maximum elements. */
        public Builder maxElements(int value) {
            budget = copyBudget(budget.timeout(), value, budget.maxDepth(), budget.maxTextLength());
            return this;
        }

        /** Convenience override for semantic tree depth. */
        public Builder maxDepth(int value) {
            budget =
                    copyBudget(
                            budget.timeout(), budget.maxElements(), value, budget.maxTextLength());
            return this;
        }

        /** Convenience override for retained text length. */
        public Builder maxTextLength(int value) {
            budget = copyBudget(budget.timeout(), budget.maxElements(), budget.maxDepth(), value);
            return this;
        }

        /** Convenience override for table row retention. */
        public Builder maxTableRows(int value) {
            budget =
                    new ObservationBudget(
                            budget.timeout(),
                            budget.maxElements(),
                            budget.maxDepth(),
                            budget.maxTextLength(),
                            value,
                            budget.maxTableColumns(),
                            budget.maxListItems(),
                            budget.maxSelectOptions());
            return this;
        }

        /** Convenience override for table column retention. */
        public Builder maxTableColumns(int value) {
            budget =
                    new ObservationBudget(
                            budget.timeout(),
                            budget.maxElements(),
                            budget.maxDepth(),
                            budget.maxTextLength(),
                            budget.maxTableRows(),
                            value,
                            budget.maxListItems(),
                            budget.maxSelectOptions());
            return this;
        }

        /** Convenience override for list item retention. */
        public Builder maxListItems(int value) {
            budget =
                    new ObservationBudget(
                            budget.timeout(),
                            budget.maxElements(),
                            budget.maxDepth(),
                            budget.maxTextLength(),
                            budget.maxTableRows(),
                            budget.maxTableColumns(),
                            value,
                            budget.maxSelectOptions());
            return this;
        }

        /** Convenience override for select option retention. */
        public Builder maxSelectOptions(int value) {
            budget =
                    new ObservationBudget(
                            budget.timeout(),
                            budget.maxElements(),
                            budget.maxDepth(),
                            budget.maxTextLength(),
                            budget.maxTableRows(),
                            budget.maxTableColumns(),
                            budget.maxListItems(),
                            value);
            return this;
        }

        /** Sets the explicit allow-list of retained data attributes. */
        public Builder allowedDataAttributes(Collection<String> values) {
            allowedDataAttributes = Set.copyOf(Objects.requireNonNull(values, "values"));
            return this;
        }

        /** Builds immutable options. */
        public ObservationOptions build() {
            return new ObservationOptions(
                    mode, includeHidden, includeInputValues, budget, allowedDataAttributes);
        }

        private ObservationBudget copyBudget(
                Duration timeout, int elements, int depth, int textLength) {
            return new ObservationBudget(
                    timeout,
                    elements,
                    depth,
                    textLength,
                    budget.maxTableRows(),
                    budget.maxTableColumns(),
                    budget.maxListItems(),
                    budget.maxSelectOptions());
        }
    }

    private static String requireDataAttribute(String value) {
        String result = Objects.requireNonNull(value, "data attribute").trim().toLowerCase();
        if (!result.startsWith("data-") || result.length() == 5) {
            throw new IllegalArgumentException("allowed attributes must be named data-*");
        }
        return result;
    }
}
