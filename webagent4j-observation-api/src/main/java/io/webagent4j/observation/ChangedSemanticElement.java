package io.webagent4j.observation;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/** Immutable before/after semantic element pair and factual changed properties. */
public record ChangedSemanticElement(
        SemanticElement before, SemanticElement after, Set<ChangedProperty> changedProperties) {

    /** Validates and freezes the change set. */
    public ChangedSemanticElement {
        Objects.requireNonNull(before, "before");
        Objects.requireNonNull(after, "after");
        EnumSet<ChangedProperty> copy = EnumSet.noneOf(ChangedProperty.class);
        copy.addAll(Objects.requireNonNull(changedProperties, "changedProperties"));
        if (copy.isEmpty()) {
            throw new IllegalArgumentException("changedProperties cannot be empty");
        }
        changedProperties = Collections.unmodifiableSet(copy);
    }
}
