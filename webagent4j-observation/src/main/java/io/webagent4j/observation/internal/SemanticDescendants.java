package io.webagent4j.observation.internal;

import io.webagent4j.observation.SemanticElement;
import io.webagent4j.observation.SemanticElementId;
import java.util.Map;

/** Shared deterministic semantic ancestry checks over captured parent relationships. */
final class SemanticDescendants {

    private SemanticDescendants() {}

    static boolean isDescendant(
            SemanticElement element,
            SemanticElementId ancestor,
            Map<String, SemanticElement> byBackendId) {
        SemanticElement current = element;
        int remaining = byBackendId.size() + 1;
        while (current.parentId().isPresent() && remaining-- > 0) {
            SemanticElementId parent = current.parentId().orElseThrow();
            if (parent.equals(ancestor)) {
                return true;
            }
            current = byBackendId.get(parent.value());
            if (current == null) {
                return false;
            }
        }
        return false;
    }
}
