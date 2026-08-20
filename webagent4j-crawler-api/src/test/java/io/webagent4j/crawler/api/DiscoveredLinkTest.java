package io.webagent4j.crawler.api;

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
                                        URL,
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
                                        URL,
                                        "a",
                                        Optional.empty(),
                                        LinkKind.ANCHOR,
                                        false,
                                        Optional.empty(),
                                        0));
    }

    @Test
    void rejectsNegativeDocumentOrder() {
        assertThatIllegalArgumentException()
                .isThrownBy(
                        () ->
                                new DiscoveredLink(
                                        URL,
                                        URL,
                                        "a",
                                        Optional.empty(),
                                        LinkKind.ANCHOR,
                                        true,
                                        Optional.empty(),
                                        -1));
    }
}
