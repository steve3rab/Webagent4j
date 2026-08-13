package io.webagent4j.observation;

import io.webagent4j.locator.api.ElementRole;
import java.util.List;
import java.util.Objects;

/** Immutable lightweight semantic-tree node; it is intentionally not a DOM node copy. */
public record SemanticTreeNode(
        SemanticElementId elementId,
        int index,
        ElementRole role,
        String name,
        List<SemanticTreeNode> children,
        boolean depthTruncated) {

    /** Validates and recursively freezes tree data. */
    public SemanticTreeNode {
        Objects.requireNonNull(elementId, "elementId");
        if (index <= 0) {
            throw new IllegalArgumentException("index must be positive");
        }
        Objects.requireNonNull(role, "role");
        name = Objects.requireNonNull(name, "name");
        children = List.copyOf(Objects.requireNonNull(children, "children"));
    }
}
