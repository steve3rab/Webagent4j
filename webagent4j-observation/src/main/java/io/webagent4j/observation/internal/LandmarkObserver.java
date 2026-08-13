package io.webagent4j.observation.internal;

import io.webagent4j.locator.api.ElementRole;
import io.webagent4j.observation.LandmarkObservation;
import io.webagent4j.observation.SemanticElement;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/** Extracts accessibility landmarks and direct semantic children. */
public final class LandmarkObserver {

    private static final Set<ElementRole> LANDMARKS =
            EnumSet.of(
                    ElementRole.BANNER,
                    ElementRole.NAVIGATION,
                    ElementRole.MAIN,
                    ElementRole.SEARCH,
                    ElementRole.FORM,
                    ElementRole.REGION,
                    ElementRole.COMPLEMENTARY,
                    ElementRole.CONTENTINFO);

    public List<LandmarkObservation> observe(List<SemanticElement> elements) {
        return elements.stream()
                .filter(element -> LANDMARKS.contains(element.role()))
                .map(
                        landmark ->
                                new LandmarkObservation(
                                        landmark.id(),
                                        landmark.role(),
                                        landmark.accessibleName(),
                                        elements.stream()
                                                .filter(
                                                        child ->
                                                                child.parentId()
                                                                        .filter(
                                                                                landmark.id()
                                                                                        ::equals)
                                                                        .isPresent())
                                                .map(SemanticElement::id)
                                                .toList()))
                .toList();
    }
}
