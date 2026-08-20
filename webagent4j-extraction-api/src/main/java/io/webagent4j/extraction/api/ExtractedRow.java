package io.webagent4j.extraction.api;

import java.util.List;
import java.util.Objects;

/** One immutable table row's cell text, in DOM column order. */
public record ExtractedRow(List<String> cells) {

    /** Defensively copies the cell list. */
    public ExtractedRow {
        cells = List.copyOf(Objects.requireNonNull(cells, "cells"));
    }

    /** Returns the cell at {@code columnIndex}, zero-based in DOM order. */
    public String cell(int columnIndex) {
        return cells.get(columnIndex);
    }

    /** Returns how many cells this row has. */
    public int size() {
        return cells.size();
    }
}
