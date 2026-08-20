package io.webagent4j.browsercrawler.internal;

import io.webagent4j.observation.Observation;
import io.webagent4j.observation.SemanticElement;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Extracts navigable links from a rendered-DOM {@link Observation}, in document order, without any
 * raw HTML parsing.
 *
 * <p>{@code Observation.links()} already filters to {@code ElementRole.LINK}, and the Playwright
 * observation backend already captures {@code href-resolved} - the browser's own absolute
 * resolution of an anchor's {@code href} against the document's current base URI - alongside the
 * raw {@code href} attribute (see {@code PlaywrightObservationBackend}'s DOM-capture script). This
 * class only reads that already-resolved value; it never re-implements relative/root-relative/
 * protocol-relative/base-href resolution, which the browser has already done correctly.
 */
public final class LinkDiscoverer {

    private LinkDiscoverer() {}

    public static List<RawLink> discover(Observation observation, URI documentBaseUrl) {
        List<SemanticElement> links = observation.links();
        List<RawLink> discovered = new ArrayList<>(links.size());
        int order = 0;
        for (SemanticElement element : links) {
            String rawHref = element.attributes().get("href");
            if (rawHref == null || rawHref.isBlank()) {
                continue;
            }
            Optional<URI> resolved = resolve(element, documentBaseUrl, rawHref);
            if (resolved.isEmpty()) {
                continue;
            }
            String anchorText =
                    !element.accessibleName().isBlank() ? element.accessibleName() : element.text();
            discovered.add(
                    new RawLink(
                            resolved.get(),
                            rawHref,
                            anchorText.isBlank() ? Optional.empty() : Optional.of(anchorText),
                            order));
            order++;
        }
        return List.copyOf(discovered);
    }

    private static Optional<URI> resolve(
            SemanticElement element, URI documentBaseUrl, String rawHref) {
        String resolvedAttribute = element.attributes().get("href-resolved");
        if (resolvedAttribute != null && !resolvedAttribute.isBlank()) {
            try {
                return Optional.of(new URI(resolvedAttribute));
            } catch (URISyntaxException ignored) {
                // fall through to manual resolution below
            }
        }
        try {
            return Optional.of(documentBaseUrl.resolve(rawHref));
        } catch (IllegalArgumentException ignored) {
            return Optional.empty();
        }
    }
}
