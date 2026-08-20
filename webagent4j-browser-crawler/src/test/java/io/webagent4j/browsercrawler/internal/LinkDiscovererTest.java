package io.webagent4j.browsercrawler.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.webagent4j.observation.Observation;
import io.webagent4j.observation.SemanticElement;
import java.net.URI;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LinkDiscovererTest {

    private static final URI PAGE_URL = URI.create("https://example.com/index.html");

    @Test
    void extractsResolvedHrefInDocumentOrder() {
        SemanticElement first =
                LinkObservationFixtures.linkElement(1, "/a", "https://example.com/a", "First");
        SemanticElement second =
                LinkObservationFixtures.linkElement(2, "/b", "https://example.com/b", "Second");
        Observation observation =
                LinkObservationFixtures.withLinks(PAGE_URL.toString(), List.of(first, second));

        List<RawLink> links = LinkDiscoverer.discover(observation, PAGE_URL);

        assertThat(links).hasSize(2);
        assertThat(links.get(0).resolvedUrl()).isEqualTo(URI.create("https://example.com/a"));
        assertThat(links.get(0).documentOrder()).isEqualTo(0);
        assertThat(links.get(1).resolvedUrl()).isEqualTo(URI.create("https://example.com/b"));
        assertThat(links.get(1).documentOrder()).isEqualTo(1);
    }

    @Test
    void fallsBackToManualResolutionWhenHrefResolvedMissing() {
        SemanticElement template =
                LinkObservationFixtures.linkElement(1, "relative", "unused", "Text");
        SemanticElement withoutResolvedHref = withAttributes(template, Map.of("href", "relative"));
        Observation observation =
                LinkObservationFixtures.withLinks(
                        PAGE_URL.toString(), List.of(withoutResolvedHref));

        List<RawLink> links = LinkDiscoverer.discover(observation, PAGE_URL);

        assertThat(links).hasSize(1);
        assertThat(links.get(0).resolvedUrl())
                .isEqualTo(URI.create("https://example.com/relative"));
    }

    @Test
    void skipsElementsWithoutAnHrefAttribute() {
        SemanticElement template =
                LinkObservationFixtures.linkElement(1, "/a", "https://example.com/a", "Text");
        SemanticElement withoutHref = withAttributes(template, Map.of());
        Observation observation =
                LinkObservationFixtures.withLinks(PAGE_URL.toString(), List.of(withoutHref));

        assertThat(LinkDiscoverer.discover(observation, PAGE_URL)).isEmpty();
    }

    private static SemanticElement withAttributes(
            SemanticElement template, Map<String, String> attributes) {
        return new SemanticElement(
                template.index(),
                template.id(),
                template.stableKey(),
                template.role(),
                template.accessibleName(),
                template.text(),
                template.tagName(),
                template.state(),
                template.reference(),
                attributes,
                template.capabilities(),
                template.parentId(),
                template.formId(),
                template.headingLevel(),
                template.fieldType(),
                template.sensitive(),
                template.value());
    }
}
