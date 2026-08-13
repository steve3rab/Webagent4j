package io.webagent4j.observation;

import java.util.List;
import java.util.Objects;

/**
 * Immutable semantic diff between two snapshots; it never compares raw DOM or volatile metadata.
 *
 * @param elementsAdded semantic elements present only after
 * @param elementsRemoved semantic elements present only before
 * @param elementsChanged matched elements with factual semantic changes
 * @param urlChanged whether the captured URL changed
 * @param titleChanged whether the title changed
 * @param dialogsOpened visible dialogs newly present after
 * @param dialogsClosed visible dialogs no longer present after
 */
public record ObservationDiff(
        List<SemanticElement> elementsAdded,
        List<SemanticElement> elementsRemoved,
        List<ChangedSemanticElement> elementsChanged,
        boolean urlChanged,
        boolean titleChanged,
        List<DialogObservation> dialogsOpened,
        List<DialogObservation> dialogsClosed) {

    /** Defensively stores diff collections. */
    public ObservationDiff {
        elementsAdded = List.copyOf(Objects.requireNonNull(elementsAdded, "elementsAdded"));
        elementsRemoved = List.copyOf(Objects.requireNonNull(elementsRemoved, "elementsRemoved"));
        elementsChanged = List.copyOf(Objects.requireNonNull(elementsChanged, "elementsChanged"));
        dialogsOpened = List.copyOf(Objects.requireNonNull(dialogsOpened, "dialogsOpened"));
        dialogsClosed = List.copyOf(Objects.requireNonNull(dialogsClosed, "dialogsClosed"));
    }

    /** Returns whether no relevant semantic or metadata change was detected. */
    public boolean empty() {
        return elementsAdded.isEmpty()
                && elementsRemoved.isEmpty()
                && elementsChanged.isEmpty()
                && !urlChanged
                && !titleChanged
                && dialogsOpened.isEmpty()
                && dialogsClosed.isEmpty();
    }
}
