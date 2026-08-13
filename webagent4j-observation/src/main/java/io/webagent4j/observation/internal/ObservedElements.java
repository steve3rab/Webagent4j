package io.webagent4j.observation.internal;

import io.webagent4j.observation.ObservationTruncation;
import io.webagent4j.observation.ObservationWarning;
import io.webagent4j.observation.SemanticElement;
import io.webagent4j.observation.SemanticElementId;
import io.webagent4j.observation.spi.SnapshotElement;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Internal immutable result of filtering and converting captured elements. */
public record ObservedElements(
        List<SemanticElement> elements,
        Map<String, SnapshotElement> snapshotsByBackendId,
        Map<String, SemanticElement> elementsByBackendId,
        Map<String, SemanticElementId> elementsByDomId,
        List<ObservationTruncation> truncations,
        List<ObservationWarning> warnings) {

    public ObservedElements {
        elements = List.copyOf(Objects.requireNonNull(elements, "elements"));
        snapshotsByBackendId = immutableMap(snapshotsByBackendId, "snapshotsByBackendId");
        elementsByBackendId = immutableMap(elementsByBackendId, "elementsByBackendId");
        elementsByDomId = immutableMap(elementsByDomId, "elementsByDomId");
        truncations = List.copyOf(Objects.requireNonNull(truncations, "truncations"));
        warnings = List.copyOf(Objects.requireNonNull(warnings, "warnings"));
    }

    private static <K, V> Map<K, V> immutableMap(Map<K, V> values, String name) {
        return Collections.unmodifiableMap(
                new LinkedHashMap<>(Objects.requireNonNull(values, name)));
    }
}
