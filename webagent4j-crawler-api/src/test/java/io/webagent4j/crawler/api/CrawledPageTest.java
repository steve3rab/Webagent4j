package io.webagent4j.crawler.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class CrawledPageTest {

    private static final URI URL = URI.create("https://example.test/");

    @Test
    void defensivelyCopiesHeadersAndRejectsFurtherMutation() {
        Map<String, List<String>> headers = new LinkedHashMap<>();
        List<String> values = new ArrayList<>(List.of("text/html"));
        headers.put("Content-Type", values);

        CrawledPage page = page(headers);
        values.add("charset=utf-8");

        assertThat(page.headers().get("Content-Type")).containsExactly("text/html");
        assertThatExceptionOfType(UnsupportedOperationException.class)
                .isThrownBy(() -> page.headers().get("Content-Type").add("x"));
        assertThatExceptionOfType(UnsupportedOperationException.class)
                .isThrownBy(() -> page.links().add(null));
    }

    @Test
    void rejectsNonSuccessStatusCode() {
        assertThatIllegalArgumentException()
                .isThrownBy(
                        () ->
                                new CrawledPage(
                                        URL,
                                        URL,
                                        0,
                                        Optional.empty(),
                                        404,
                                        Map.of(),
                                        "text/html",
                                        Optional.empty(),
                                        "<html></html>",
                                        Optional.empty(),
                                        Optional.empty(),
                                        List.of(),
                                        List.of(),
                                        0,
                                        Duration.ZERO,
                                        provenance()));
    }

    private static CrawledPage page(Map<String, List<String>> headers) {
        return new CrawledPage(
                URL,
                URL,
                0,
                Optional.empty(),
                200,
                headers,
                "text/html",
                Optional.empty(),
                "<html></html>",
                Optional.empty(),
                Optional.empty(),
                List.of(),
                List.of(),
                12,
                Duration.ofMillis(5),
                provenance());
    }

    private static CrawlPageProvenance provenance() {
        return new CrawlPageProvenance(URL, Optional.empty(), 0, URL, URL, List.of());
    }
}
