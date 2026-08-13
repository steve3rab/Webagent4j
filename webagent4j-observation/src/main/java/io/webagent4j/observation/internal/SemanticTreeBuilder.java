package io.webagent4j.observation.internal;

import io.webagent4j.observation.ObservationTruncation;
import io.webagent4j.observation.ObservationTruncationType;
import io.webagent4j.observation.SemanticElement;
import io.webagent4j.observation.SemanticElementId;
import io.webagent4j.observation.SemanticTree;
import io.webagent4j.observation.SemanticTreeNode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Builds a bounded semantic hierarchy from meaningful parent relationships. */
public final class SemanticTreeBuilder {

    public TreeResult build(List<SemanticElement> elements, int maxDepth) {
        Map<SemanticElementId, List<SemanticElement>> children = new LinkedHashMap<>();
        List<SemanticElement> roots = new ArrayList<>();
        Set<SemanticElementId> known = new HashSet<>();
        elements.forEach(element -> known.add(element.id()));
        for (SemanticElement element : elements) {
            if (element.parentId().filter(known::contains).isPresent()) {
                children.computeIfAbsent(
                                element.parentId().orElseThrow(), ignored -> new ArrayList<>())
                        .add(element);
            } else {
                roots.add(element);
            }
        }
        List<ObservationTruncation> truncations = new ArrayList<>();
        List<SemanticTreeNode> rootNodes =
                roots.stream()
                        .map(
                                root ->
                                        node(
                                                root,
                                                children,
                                                1,
                                                maxDepth,
                                                new HashSet<>(),
                                                truncations))
                        .toList();
        return new TreeResult(new SemanticTree(rootNodes, !truncations.isEmpty()), truncations);
    }

    private static SemanticTreeNode node(
            SemanticElement element,
            Map<SemanticElementId, List<SemanticElement>> children,
            int depth,
            int maxDepth,
            Set<SemanticElementId> path,
            List<ObservationTruncation> truncations) {
        if (!path.add(element.id())) {
            return new SemanticTreeNode(
                    element.id(),
                    element.index(),
                    element.role(),
                    element.accessibleName(),
                    List.of(),
                    true);
        }
        List<SemanticElement> direct = children.getOrDefault(element.id(), List.of());
        boolean truncated = depth >= maxDepth && !direct.isEmpty();
        List<SemanticTreeNode> nested;
        if (truncated) {
            truncations.add(
                    new ObservationTruncation(
                            ObservationTruncationType.TREE_DEPTH,
                            direct.size(),
                            0,
                            Optional.of(element.id())));
            nested = List.of();
        } else {
            nested =
                    direct.stream()
                            .map(
                                    child ->
                                            node(
                                                    child,
                                                    children,
                                                    depth + 1,
                                                    maxDepth,
                                                    new HashSet<>(path),
                                                    truncations))
                            .toList();
        }
        return new SemanticTreeNode(
                element.id(),
                element.index(),
                element.role(),
                element.accessibleName().isBlank() ? element.text() : element.accessibleName(),
                nested,
                truncated);
    }

    public record TreeResult(SemanticTree tree, List<ObservationTruncation> truncations) {

        public TreeResult {
            truncations = List.copyOf(truncations);
        }
    }
}
