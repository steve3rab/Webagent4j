package io.webagent4j.observation;

import io.webagent4j.locator.api.ElementRole;
import io.webagent4j.observation.spi.SnapshotElement;
import java.util.Set;

/** Default visibility, decoration, and semantic-relevance filter. */
public final class SemanticObservationFilter implements IObservationFilter {

    private static final Set<String> TEXT_TAGS = Set.of("p", "blockquote", "pre");

    @Override
    public boolean include(SnapshotElement element, ObservationOptions options) {
        if (!options.includeHidden() && !element.state().interaction().visible()) {
            return false;
        }
        if (element.role() == ElementRole.IMAGE
                && element.accessibleName().isBlank()
                && element.attributes().getOrDefault("alt", "").isBlank()) {
            return false;
        }
        if (element.role() == ElementRole.UNKNOWN) {
            return TEXT_TAGS.contains(element.tagName()) && !element.text().isBlank();
        }
        return true;
    }
}
