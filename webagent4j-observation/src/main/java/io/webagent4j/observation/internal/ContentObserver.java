package io.webagent4j.observation.internal;

import io.webagent4j.locator.api.ElementRole;
import io.webagent4j.observation.HeadingObservation;
import io.webagent4j.observation.ObservationWarning;
import io.webagent4j.observation.ObservationWarningType;
import io.webagent4j.observation.SemanticElement;
import io.webagent4j.observation.SemanticElementId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Extracts bounded headings and important visible text without copying full inner text. */
public final class ContentObserver {

    public ContentResult observe(List<SemanticElement> elements) {
        List<HeadingObservation> headings = new ArrayList<>();
        List<String> textBlocks = new ArrayList<>();
        List<ObservationWarning> warnings = new ArrayList<>();
        SemanticElementId[] hierarchy = new SemanticElementId[7];
        int previousLevel = 0;
        for (SemanticElement element : elements) {
            if (element.role() == ElementRole.HEADING && element.headingLevel().isPresent()) {
                int level = element.headingLevel().orElseThrow();
                if (previousLevel > 0 && level > previousLevel + 1) {
                    warnings.add(
                            new ObservationWarning(
                                    ObservationWarningType.HEADING_LEVEL_JUMP,
                                    "Heading level jumps from " + previousLevel + " to " + level,
                                    Optional.of(element.id())));
                }
                SemanticElementId parent = null;
                for (int candidate = level - 1; candidate >= 1; candidate--) {
                    if (hierarchy[candidate] != null) {
                        parent = hierarchy[candidate];
                        break;
                    }
                }
                headings.add(
                        new HeadingObservation(
                                element.id(),
                                element.index(),
                                element.accessibleName().isBlank()
                                        ? element.text()
                                        : element.accessibleName(),
                                level,
                                Optional.ofNullable(parent)));
                hierarchy[level] = element.id();
                for (int deeper = level + 1; deeper <= 6; deeper++) {
                    hierarchy[deeper] = null;
                }
                previousLevel = level;
            } else if (element.role() == ElementRole.UNKNOWN
                    && element.visible()
                    && !element.text().isBlank()
                    && !textBlocks.contains(element.text())) {
                textBlocks.add(element.text());
            }
        }
        return new ContentResult(headings, textBlocks, warnings);
    }

    public record ContentResult(
            List<HeadingObservation> headings,
            List<String> textBlocks,
            List<ObservationWarning> warnings) {

        public ContentResult {
            headings = List.copyOf(headings);
            textBlocks = List.copyOf(textBlocks);
            warnings = List.copyOf(warnings);
        }
    }
}
