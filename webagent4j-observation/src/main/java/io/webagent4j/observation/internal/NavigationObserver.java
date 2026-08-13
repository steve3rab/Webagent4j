package io.webagent4j.observation.internal;

import io.webagent4j.locator.api.ElementRole;
import io.webagent4j.observation.NavigationObservation;
import io.webagent4j.observation.NavigationOrientation;
import io.webagent4j.observation.SemanticElement;
import io.webagent4j.observation.SemanticRelationship;
import io.webagent4j.observation.SemanticRelationshipType;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** Extracts explicit navigation regions, current items, and owned links. */
public final class NavigationObserver {

    public NavigationResult observe(ObservedElements observed) {
        List<NavigationObservation> navigations = new ArrayList<>();
        List<SemanticRelationship> relationships = new ArrayList<>();
        for (SemanticElement navigation : observed.elements()) {
            if (navigation.role() != ElementRole.NAVIGATION) {
                continue;
            }
            List<SemanticElement> links =
                    observed.elements().stream()
                            .filter(element -> element.role() == ElementRole.LINK)
                            .filter(
                                    element ->
                                            SemanticDescendants.isDescendant(
                                                    element,
                                                    navigation.id(),
                                                    observed.elementsByBackendId()))
                            .toList();
            links.forEach(
                    link ->
                            relationships.add(
                                    new SemanticRelationship(
                                            navigation.id(),
                                            link.id(),
                                            SemanticRelationshipType.OWNS)));
            Optional<io.webagent4j.observation.SemanticElementId> current =
                    links.stream()
                            .filter(
                                    link ->
                                            !link.attributes()
                                                    .getOrDefault("aria-current", "")
                                                    .equalsIgnoreCase("false"))
                            .filter(link -> link.attributes().containsKey("aria-current"))
                            .map(SemanticElement::id)
                            .findFirst();
            navigations.add(
                    new NavigationObservation(
                            navigation.id(),
                            navigation.accessibleName(),
                            links,
                            current,
                            orientation(navigation.attributes().get("aria-orientation"))));
        }
        return new NavigationResult(navigations, relationships);
    }

    private static NavigationOrientation orientation(String value) {
        if (value == null) {
            return NavigationOrientation.UNKNOWN;
        }
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "horizontal" -> NavigationOrientation.HORIZONTAL;
            case "vertical" -> NavigationOrientation.VERTICAL;
            default -> NavigationOrientation.UNKNOWN;
        };
    }

    public record NavigationResult(
            List<NavigationObservation> navigations, List<SemanticRelationship> relationships) {

        public NavigationResult {
            navigations = List.copyOf(navigations);
            relationships = List.copyOf(relationships);
        }
    }
}
