package io.webagent4j.observation.internal;

import io.webagent4j.locator.api.ElementRole;
import io.webagent4j.observation.ImageObservation;
import io.webagent4j.observation.SemanticElement;
import io.webagent4j.observation.spi.SnapshotElement;
import java.util.List;
import java.util.Optional;

/** Extracts meaningful image metadata without downloading image content. */
public final class ImageObserver {

    public List<ImageObservation> observe(ObservedElements observed) {
        return observed.elements().stream()
                .filter(element -> element.role() == ElementRole.IMAGE)
                .map(
                        element ->
                                image(
                                        element,
                                        observed.snapshotsByBackendId().get(element.id().value())))
                .toList();
    }

    private static ImageObservation image(SemanticElement element, SnapshotElement snapshot) {
        return new ImageObservation(
                element.id(),
                element.accessibleName(),
                Optional.ofNullable(element.attributes().get("alt")),
                Optional.ofNullable(element.attributes().get("src-resolved"))
                        .or(() -> Optional.ofNullable(element.attributes().get("src"))),
                snapshot == null ? 0 : snapshot.width(),
                snapshot == null ? 0 : snapshot.height());
    }
}
