package io.webagent4j.observation;

import java.util.List;
import java.util.Objects;

/** Immutable forest of top-level semantic page regions in document order. */
public record SemanticTree(List<SemanticTreeNode> roots, boolean depthTruncated) {

    /** Defensively stores semantic roots. */
    public SemanticTree {
        roots = List.copyOf(Objects.requireNonNull(roots, "roots"));
    }
}
