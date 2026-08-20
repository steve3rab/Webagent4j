package io.webagent4j.crawler.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.net.URI;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class DiscoveredLinkTest {

    private static final URI URL = URI.create("https://example.test/a");

    @Test
    void anAllowedLinkCannotCarryARejection() {
        assertThatIllegalArgumentException()
                .isThrownBy(
                        () ->
                                new DiscoveredLink(
                                        URL,
                                        Optional.of(URL),
                                        "a",
                                        Optional.empty(),
                                        LinkKind.ANCHOR,
                                        true,
                                        Optional.of(
                                                CrawlDecision.reject(
                                                        CrawlDecisionType.REJECT_HOST,
                                                        "wrong host")),
                                        0));
    }

    @Test
    void aRejectedLinkMustCarryARejection() {
        assertThatIllegalArgumentException()
                .isThrownBy(
                        () ->
                                new DiscoveredLink(
                                        URL,
                                        Optional.of(URL),
                                        "a",
                                        Optional.empty(),
                                        LinkKind.ANCHOR,
                                        false,
                                        Optional.empty(),
                                        0));
    }

    @Test
    void anAllowedLinkMustCarryANormalizedUrl() {
        assertThatIllegalArgumentException()
                .isThrownBy(
                        () ->
                                new DiscoveredLink(
                                        URL,
                                        Optional.empty(),
                                        "a",
                                        Optional.empty(),
                                        LinkKind.ANCHOR,
                                        true,
                                        Optional.empty(),
                                        0));
    }

    @Test
    void aLinkRejectedBeforeNormalizationCarriesNoNormalizedUrl() {
        DiscoveredLink rejected =
                new DiscoveredLink(
                        URI.create("mailto:test@example.test"),
                        Optional.empty(),
                        "mailto:test@example.test",
                        Optional.empty(),
                        LinkKind.ANCHOR,
                        false,
                        Optional.of(
                                CrawlDecision.reject(
                                        CrawlDecisionType.REJECT_SCHEME, "scheme not allowed")),
                        0);

        assertThat(rejected.normalizedUrl()).isEmpty();
    }

    @Test
    void rejectsNegativeDocumentOrder() {
        assertThatIllegalArgumentException()
                .isThrownBy(
                        () ->
                                new DiscoveredLink(
                                        URL,
                                        Optional.of(URL),
                                        "a",
                                        Optional.empty(),
                                        LinkKind.ANCHOR,
                                        true,
                                        Optional.empty(),
                                        -1));
    }
}
