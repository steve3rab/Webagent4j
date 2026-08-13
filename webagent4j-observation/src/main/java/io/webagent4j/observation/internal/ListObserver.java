package io.webagent4j.observation.internal;

import io.webagent4j.locator.api.ElementRole;
import io.webagent4j.observation.ListObservation;
import io.webagent4j.observation.ObservationTruncation;
import io.webagent4j.observation.ObservationTruncationType;
import io.webagent4j.observation.SemanticElement;
import io.webagent4j.observation.spi.SnapshotElement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Builds bounded native and explicit ARIA list summaries. */
public final class ListObserver {

    public ListResult observe(ObservedElements observed) {
        List<ListObservation> lists = new ArrayList<>();
        List<ObservationTruncation> truncations = new ArrayList<>();
        for (SemanticElement element : observed.elements()) {
            if (element.role() != ElementRole.LIST) {
                continue;
            }
            SnapshotElement snapshot = observed.snapshotsByBackendId().get(element.id().value());
            if (snapshot == null) {
                continue;
            }
            boolean truncated = snapshot.listItemCount() > snapshot.listItems().size();
            lists.add(
                    new ListObservation(
                            element.id(),
                            element.tagName().equals("ol"),
                            snapshot.listItemCount(),
                            snapshot.listItems(),
                            truncated));
            if (truncated) {
                truncations.add(
                        new ObservationTruncation(
                                ObservationTruncationType.LIST_ITEMS,
                                snapshot.listItemCount(),
                                snapshot.listItems().size(),
                                Optional.of(element.id())));
            }
        }
        return new ListResult(lists, truncations);
    }

    public record ListResult(List<ListObservation> lists, List<ObservationTruncation> truncations) {

        public ListResult {
            lists = List.copyOf(lists);
            truncations = List.copyOf(truncations);
        }
    }
}
