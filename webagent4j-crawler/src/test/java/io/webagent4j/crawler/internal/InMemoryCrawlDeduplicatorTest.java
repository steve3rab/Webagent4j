package io.webagent4j.crawler.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import org.junit.jupiter.api.Test;

class InMemoryCrawlDeduplicatorTest {

    @Test
    void firstClaimOfAUrlSucceeds() {
        InMemoryCrawlDeduplicator dedup = new InMemoryCrawlDeduplicator();

        assertThat(dedup.tryClaim(URI.create("https://example.test/a"))).isTrue();
    }

    @Test
    void secondClaimOfTheSameNormalizedUrlFails() {
        InMemoryCrawlDeduplicator dedup = new InMemoryCrawlDeduplicator();
        URI url = URI.create("https://example.test/a");

        dedup.tryClaim(url);

        assertThat(dedup.tryClaim(url)).isFalse();
    }

    @Test
    void differentUrlsClaimIndependently() {
        InMemoryCrawlDeduplicator dedup = new InMemoryCrawlDeduplicator();

        assertThat(dedup.tryClaim(URI.create("https://example.test/a"))).isTrue();
        assertThat(dedup.tryClaim(URI.create("https://example.test/b"))).isTrue();
    }
}
