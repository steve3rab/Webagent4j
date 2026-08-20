package io.webagent4j.crawler;

import static org.assertj.core.api.Assertions.assertThat;

import io.webagent4j.crawler.api.LinkKind;
import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.Test;

class JsoupHtmlLinkExtractorTest {

    private static final URI BASE = URI.create("https://example.test/a/index.html");

    private final JsoupHtmlLinkExtractor extractor = new JsoupHtmlLinkExtractor();

    @Test
    void resolvesARelativeHref() {
        LinkExtractionResult result = extractor.extract("<a href=\"products\">Products</a>", BASE);

        assertThat(result.links()).hasSize(1);
        assertThat(result.links().get(0).resolvedUrl())
                .isEqualTo(URI.create("https://example.test/a/products"));
    }

    @Test
    void resolvesADotDotRelativeHref() {
        LinkExtractionResult result = extractor.extract("<a href=\"../b\">B</a>", BASE);

        assertThat(result.links().get(0).resolvedUrl())
                .isEqualTo(URI.create("https://example.test/b"));
    }

    @Test
    void resolvesARootRelativeHref() {
        LinkExtractionResult result = extractor.extract("<a href=\"/root\">Root</a>", BASE);

        assertThat(result.links().get(0).resolvedUrl())
                .isEqualTo(URI.create("https://example.test/root"));
    }

    @Test
    void keepsAnAbsoluteHrefUnchanged() {
        LinkExtractionResult result =
                extractor.extract("<a href=\"https://other.test/x\">X</a>", BASE);

        assertThat(result.links().get(0).resolvedUrl())
                .isEqualTo(URI.create("https://other.test/x"));
    }

    @Test
    void resolvesAProtocolRelativeHref() {
        LinkExtractionResult result =
                extractor.extract("<a href=\"//other.test/path\">X</a>", BASE);

        assertThat(result.links().get(0).resolvedUrl())
                .isEqualTo(URI.create("https://other.test/path"));
    }

    @Test
    void respectsBaseHref() {
        LinkExtractionResult result =
                extractor.extract(
                        "<html><head><base href=\"/catalog/\"></head>"
                                + "<body><a href=\"item\">Item</a></body></html>",
                        BASE);

        assertThat(result.links().get(0).resolvedUrl())
                .isEqualTo(URI.create("https://example.test/catalog/item"));
    }

    @Test
    void tolerantOfMalformedHtml() {
        LinkExtractionResult result =
                extractor.extract(
                        "<HTML><BODY><a href=products>Products<a href=\"/about\">About", BASE);

        assertThat(result.links()).hasSizeGreaterThanOrEqualTo(2);
    }

    @Test
    void extractsMailtoAndJavascriptLinksRatherThanSilentlyDroppingThem() {
        LinkExtractionResult result =
                extractor.extract(
                        "<a href=\"mailto:a@example.test\">Mail</a>"
                                + "<a href=\"javascript:void(0)\">JS</a>",
                        BASE);

        assertThat(result.links())
                .extracting(link -> link.resolvedUrl().getScheme())
                .containsExactlyInAnyOrder("mailto", "javascript");
    }

    @Test
    void skipsAMalformedHrefWithoutFailingTheWholePage() {
        LinkExtractionResult result =
                extractor.extract(
                        "<a href=\"http://[unterminated\">Bad</a><a href=\"/ok\">Ok</a>", BASE);

        assertThat(result.links()).hasSize(1);
        assertThat(result.links().get(0).resolvedUrl())
                .isEqualTo(URI.create("https://example.test/ok"));
    }

    @Test
    void anEmptyHrefResolvesToTheCurrentPageItself() {
        LinkExtractionResult result = extractor.extract("<a href=\"\">Self</a>", BASE);

        assertThat(result.links()).hasSize(1);
        assertThat(result.links().get(0).resolvedUrl()).isEqualTo(BASE);
    }

    @Test
    void preservesDocumentOrder() {
        LinkExtractionResult result =
                extractor.extract(
                        "<a href=\"/b\">B</a><a href=\"/a\">A</a><a href=\"/c\">C</a>", BASE);

        List<String> hrefs = result.links().stream().map(link -> link.rawHref()).toList();
        assertThat(hrefs).containsExactly("/b", "/a", "/c");
        assertThat(result.links())
                .extracting(link -> link.documentOrder())
                .containsExactly(0, 1, 2);
    }

    @Test
    void extractsAreaLinksAsAreaKind() {
        LinkExtractionResult result =
                extractor.extract("<map><area href=\"/region\" shape=\"rect\"></map>", BASE);

        assertThat(result.links().get(0).kind()).isEqualTo(LinkKind.AREA);
    }

    @Test
    void extractsAnchorTextTrimmed() {
        LinkExtractionResult result = extractor.extract("<a href=\"/x\">  Hello World  </a>", BASE);

        assertThat(result.links().get(0).anchorText()).contains("Hello World");
    }

    @Test
    void extractsTitle() {
        LinkExtractionResult result =
                extractor.extract("<html><head><title>My Page</title></head></html>", BASE);

        assertThat(result.title()).contains("My Page");
    }

    @Test
    void extractsAndResolvesDeclaredCanonicalUrl() {
        LinkExtractionResult result =
                extractor.extract("<link rel=\"canonical\" href=\"/canonical-path\">", BASE);

        assertThat(result.declaredCanonicalUrl())
                .contains(URI.create("https://example.test/canonical-path"));
    }

    @Test
    void noCanonicalMeansEmpty() {
        LinkExtractionResult result = extractor.extract("<p>No canonical here</p>", BASE);

        assertThat(result.declaredCanonicalUrl()).isEmpty();
    }
}
