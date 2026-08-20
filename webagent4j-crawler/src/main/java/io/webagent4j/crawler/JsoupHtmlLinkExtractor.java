package io.webagent4j.crawler;

import io.webagent4j.crawler.api.LinkKind;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

/**
 * {@link IHtmlLinkExtractor} backed by <a href="https://jsoup.org">jsoup</a>, a real, malformed-
 * HTML-tolerant parser - never a regular expression.
 *
 * <p>{@code <base href>} is resolved automatically by jsoup's own parser as it encounters the tag,
 * exactly matching normal browser behavior: every subsequent relative {@code href} on the page
 * resolves against it. A missing or invalid {@code <base href>} leaves jsoup's base URI as the
 * document's own URL - the required fallback - without failing the whole page.
 */
public final class JsoupHtmlLinkExtractor implements IHtmlLinkExtractor {

    @Override
    public LinkExtractionResult extract(String html, URI baseUri) {
        Document document = Jsoup.parse(html, baseUri.toString());

        List<ExtractedLink> links = new ArrayList<>();
        Elements anchorsAndAreas = document.select("a[href], area[href]");
        int documentOrder = 0;
        for (Element element : anchorsAndAreas) {
            Optional<ExtractedLink> link = toExtractedLink(element, documentOrder);
            if (link.isPresent()) {
                links.add(link.get());
                documentOrder++;
            }
        }

        String titleText = document.title();
        Optional<String> title = titleText.isBlank() ? Optional.empty() : Optional.of(titleText);

        Optional<URI> canonical = extractCanonical(document);

        return new LinkExtractionResult(links, title, canonical);
    }

    private static Optional<ExtractedLink> toExtractedLink(Element element, int documentOrder) {
        String rawHref = element.attr("href");
        String absoluteHref = element.absUrl("href");
        if (absoluteHref.isBlank()) {
            return Optional.empty();
        }
        URI resolved = parseQuietly(absoluteHref);
        if (resolved == null) {
            return Optional.empty();
        }
        String text = element.text().trim();
        Optional<String> anchorText = text.isBlank() ? Optional.empty() : Optional.of(text);
        LinkKind kind =
                element.tagName().equalsIgnoreCase("area") ? LinkKind.AREA : LinkKind.ANCHOR;
        return Optional.of(new ExtractedLink(resolved, rawHref, anchorText, kind, documentOrder));
    }

    private static Optional<URI> extractCanonical(Document document) {
        Element canonical = document.selectFirst("link[rel=canonical][href]");
        if (canonical == null) {
            return Optional.empty();
        }
        String absoluteHref = canonical.absUrl("href");
        if (absoluteHref.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(parseQuietly(absoluteHref));
    }

    private static URI parseQuietly(String absoluteUrl) {
        try {
            return new URI(absoluteUrl);
        } catch (URISyntaxException malformed) {
            return null;
        }
    }
}
