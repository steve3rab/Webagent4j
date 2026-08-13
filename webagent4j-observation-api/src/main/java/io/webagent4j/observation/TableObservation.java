package io.webagent4j.observation;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Immutable bounded semantic table summary. */
public record TableObservation(
        SemanticElementId elementId,
        String name,
        List<String> headers,
        int rowCount,
        int columnCount,
        List<List<String>> rows,
        boolean rowsTruncated,
        boolean columnsTruncated) {

    /** Validates and deeply copies table data. */
    public TableObservation {
        Objects.requireNonNull(elementId, "elementId");
        name = Objects.requireNonNull(name, "name");
        headers = List.copyOf(Objects.requireNonNull(headers, "headers"));
        if (rowCount < 0 || columnCount < 0) {
            throw new IllegalArgumentException("table counts cannot be negative");
        }
        List<List<String>> copy = new ArrayList<>();
        Objects.requireNonNull(rows, "rows").forEach(row -> copy.add(List.copyOf(row)));
        rows = List.copyOf(copy);
    }
}
