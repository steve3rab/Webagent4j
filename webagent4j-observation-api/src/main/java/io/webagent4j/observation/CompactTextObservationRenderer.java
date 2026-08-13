package io.webagent4j.observation;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Deterministic compact semantic-tree renderer for diagnostics and future agent consumers. */
public final class CompactTextObservationRenderer implements IObservationRenderer<String> {

    @Override
    public String render(Observation observation) {
        Objects.requireNonNull(observation, "observation");
        StringBuilder result =
                new StringBuilder("PAGE ")
                        .append(quoted(observation.title()))
                        .append(System.lineSeparator());
        Map<SemanticElementId, SemanticElement> elements = new LinkedHashMap<>();
        observation.elements().forEach(element -> elements.put(element.id(), element));
        observation.tree().roots().forEach(node -> render(node, elements, result, 0));
        if (observation.statistics().truncated()) {
            result.append("TRUNCATED").append(System.lineSeparator());
        }
        return result.toString().stripTrailing();
    }

    private static void render(
            SemanticTreeNode node,
            Map<SemanticElementId, SemanticElement> elements,
            StringBuilder output,
            int depth) {
        output.append("  ".repeat(depth))
                .append('[')
                .append(node.index())
                .append("] ")
                .append(node.role());
        if (!node.name().isBlank()) {
            output.append(' ').append(quoted(node.name()));
        }
        SemanticElement element = elements.get(node.elementId());
        if (element != null) {
            element.headingLevel().ifPresent(level -> output.append(" level=").append(level));
            element.fieldType().ifPresent(type -> output.append(" type=").append(type));
            if (element.state().required()) {
                output.append(" required");
            }
            if (element.state().readOnly()) {
                output.append(" readonly");
            }
            if (!element.visible()) {
                output.append(" hidden");
            }
            if (!element.enabled()) {
                output.append(" disabled");
            }
            if (element.sensitive()) {
                output.append(" sensitive");
            }
            if (!element.capabilities().isEmpty()) {
                output.append(" capabilities=").append(element.capabilities());
            }
        }
        if (node.depthTruncated()) {
            output.append(" depth-truncated");
        }
        output.append(System.lineSeparator());
        node.children().forEach(child -> render(child, elements, output, depth + 1));
    }

    private static String quoted(String value) {
        return '"' + value.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }
}
