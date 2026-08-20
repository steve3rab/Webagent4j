package io.webagent4j.browsercrawler.internal;

import io.webagent4j.dom.ElementState;
import io.webagent4j.locator.api.ElementReference;
import io.webagent4j.locator.api.ElementRole;
import io.webagent4j.locator.api.LocatorDefinition;
import io.webagent4j.observation.ObservationId;
import io.webagent4j.observation.ObservationStatistics;
import io.webagent4j.observation.ObservedElementState;
import io.webagent4j.observation.ObservedValue;
import io.webagent4j.observation.PageMetadata;
import io.webagent4j.observation.SemanticElement;
import io.webagent4j.observation.SemanticElementId;
import io.webagent4j.observation.SemanticTree;
import io.webagent4j.observation.ViewportSize;
import io.webagent4j.observation.internal.ObservationBuilder;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Minimal, real (never mocked) {@code Observation}/{@code SemanticElement} construction for tests.
 */
public final class LinkObservationFixtures {

    private LinkObservationFixtures() {}

    /** One {@code <a href>} link element with the given raw href and (already-resolved) target. */
    public static SemanticElement linkElement(
            int index, String rawHref, String resolvedHref, String text) {
        ElementState interaction =
                new ElementState(
                        true, true, true, false, false, false, false, false, true, true, false,
                        true);
        return new SemanticElement(
                index,
                new SemanticElementId("element-" + index),
                "LINK|" + text,
                ElementRole.LINK,
                text,
                text,
                "a",
                new ObservedElementState(interaction, false, Optional.empty()),
                new ElementReference(LocatorDefinition.forRole(ElementRole.LINK).named(text)),
                Map.of("href", rawHref, "href-resolved", resolvedHref),
                Set.of(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                false,
                ObservedValue.empty());
    }

    /** A minimal, valid {@code Observation} whose {@code links()} returns exactly {@code links}. */
    public static io.webagent4j.observation.Observation withLinks(
            String pageUrl, List<SemanticElement> links) {
        PageMetadata metadata =
                new PageMetadata(
                        pageUrl,
                        "Test page",
                        Optional.empty(),
                        Optional.empty(),
                        "complete",
                        Instant.EPOCH,
                        new ViewportSize(1024, 768),
                        Optional.empty(),
                        Optional.empty());
        ObservationStatistics statistics =
                new ObservationStatistics(
                        links.size(),
                        links.size(),
                        0,
                        0,
                        0,
                        links.size(),
                        0,
                        0,
                        Duration.ZERO,
                        List.of());
        return new ObservationBuilder(new ObservationId("test-observation"), metadata)
                .semantic(links, List.of(), List.of(), new SemanticTree(List.of(), false))
                .diagnostics(statistics, List.of())
                .build();
    }
}
