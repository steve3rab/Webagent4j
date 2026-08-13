package io.webagent4j.observation;

import java.util.Objects;

/** Immutable relationship between two elements in the same observation. */
public record SemanticRelationship(
        SemanticElementId source, SemanticElementId target, SemanticRelationshipType type) {

    /** Validates relationship endpoints and type. */
    public SemanticRelationship {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(type, "type");
    }
}
