package io.webagent4j.extraction.api;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * An immutable, structured reading of one accessible HTML table ({@code table}/{@code thead}/
 * {@code tbody}/{@code tr}/{@code th}/{@code td}). Column access is always by DOM order; {@link
 * #cell(int, String)} additionally resolves a header name to its column when headers were read.
 */
public record ExtractedTable(List<String> headers, List<ExtractedRow> rows) {

    /** Defensively copies both lists. */
    public ExtractedTable {
        headers = List.copyOf(Objects.requireNonNull(headers, "headers"));
        rows = List.copyOf(Objects.requireNonNull(rows, "rows"));
    }

    /** Returns the cell at {@code (rowIndex, columnIndex)}, both zero-based in DOM order. */
    public String cell(int rowIndex, int columnIndex) {
        return rows.get(rowIndex).cell(columnIndex);
    }

    /**
     * Returns the cell at {@code rowIndex} under the column named {@code header}, or empty when
     * {@code header} does not match any of {@link #headers()} exactly.
     */
    public Optional<String> cell(int rowIndex, String header) {
        int columnIndex = headers.indexOf(header);
        return columnIndex < 0 ? Optional.empty() : Optional.of(cell(rowIndex, columnIndex));
    }
}
