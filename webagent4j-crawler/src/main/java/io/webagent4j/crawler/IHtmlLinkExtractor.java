package io.webagent4j.crawler;

import java.net.URI;

/**
 * Extracts navigation links, the document title, and the declared canonical URL from one HTML
 * document. Implementations must use a real HTML parser, tolerant of malformed markup - never a
 * regular expression.
 */
@FunctionalInterface
public interface IHtmlLinkExtractor {

    /**
     * @param html the document's decoded HTML
     * @param baseUri the document's own URL, used to resolve relative hrefs absent a valid {@code
     *     <base href>}
     */
    LinkExtractionResult extract(String html, URI baseUri);
}
