package io.webagent4j.observation.internal;

import io.webagent4j.locator.api.ElementRole;
import io.webagent4j.observation.ObservationTruncation;
import io.webagent4j.observation.ObservationTruncationType;
import io.webagent4j.observation.SemanticElement;
import io.webagent4j.observation.TableObservation;
import io.webagent4j.observation.spi.SnapshotElement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Builds bounded table summaries and explicit truncation records. */
public final class TableObserver {

    public TableResult observe(ObservedElements observed) {
        List<TableObservation> tables = new ArrayList<>();
        List<ObservationTruncation> truncations = new ArrayList<>();
        for (SemanticElement element : observed.elements()) {
            if (element.role() != ElementRole.TABLE && element.role() != ElementRole.GRID) {
                continue;
            }
            SnapshotElement snapshot = observed.snapshotsByBackendId().get(element.id().value());
            if (snapshot == null) {
                continue;
            }
            boolean rowsTruncated = snapshot.tableRowCount() > snapshot.tableRows().size();
            int retainedColumns =
                    snapshot.tableRows().stream()
                            .mapToInt(List::size)
                            .max()
                            .orElse(snapshot.tableHeaders().size());
            boolean columnsTruncated = snapshot.tableColumnCount() > retainedColumns;
            tables.add(
                    new TableObservation(
                            element.id(),
                            element.accessibleName(),
                            snapshot.tableHeaders(),
                            snapshot.tableRowCount(),
                            snapshot.tableColumnCount(),
                            snapshot.tableRows(),
                            rowsTruncated,
                            columnsTruncated));
            if (rowsTruncated) {
                truncations.add(
                        truncation(
                                ObservationTruncationType.TABLE_ROWS,
                                snapshot.tableRowCount(),
                                snapshot.tableRows().size(),
                                element));
            }
            if (columnsTruncated) {
                truncations.add(
                        truncation(
                                ObservationTruncationType.TABLE_COLUMNS,
                                snapshot.tableColumnCount(),
                                retainedColumns,
                                element));
            }
        }
        return new TableResult(tables, truncations);
    }

    private static ObservationTruncation truncation(
            ObservationTruncationType type, int original, int retained, SemanticElement element) {
        return new ObservationTruncation(type, original, retained, Optional.of(element.id()));
    }

    public record TableResult(
            List<TableObservation> tables, List<ObservationTruncation> truncations) {

        public TableResult {
            tables = List.copyOf(tables);
            truncations = List.copyOf(truncations);
        }
    }
}
