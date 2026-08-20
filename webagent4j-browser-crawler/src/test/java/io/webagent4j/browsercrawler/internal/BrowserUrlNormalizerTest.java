package io.webagent4j.browsercrawler.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.webagent4j.crawler.api.QueryParameterPolicy;
import java.net.URI;
import org.junit.jupiter.api.Test;

class BrowserUrlNormalizerTest {

    private final BrowserUrlNormalizer normalizer =
            new BrowserUrlNormalizer(QueryParameterPolicy.keepAll());

    @Test
    void lowercasesSchemeAndHost() {
        assertThat(normalizer.normalize(URI.create("HTTPS://Example.COM/Path")).toString())
                .isEqualTo("https://example.com/Path");
    }

    @Test
    void dropsFragment() {
        assertThat(normalizer.normalize(URI.create("https://example.com/page#section")).toString())
                .isEqualTo("https://example.com/page");
    }

    @Test
    void dropsDefaultHttpsPort() {
        assertThat(normalizer.normalize(URI.create("https://example.com:443/page")).toString())
                .isEqualTo("https://example.com/page");
    }

    @Test
    void keepsNonDefaultPort() {
        assertThat(normalizer.normalize(URI.create("https://example.com:8443/page")).toString())
                .isEqualTo("https://example.com:8443/page");
    }

    @Test
    void emptyPathBecomesRoot() {
        assertThat(normalizer.normalize(URI.create("https://example.com")).toString())
                .isEqualTo("https://example.com/");
    }

    @Test
    void dropsKnownTrackingParametersWhenConfigured() {
        BrowserUrlNormalizer trackingAware =
                new BrowserUrlNormalizer(QueryParameterPolicy.dropKnownTracking());
        assertThat(
                        trackingAware
                                .normalize(URI.create("https://example.com/page?utm_source=x&id=1"))
                                .toString())
                .isEqualTo("https://example.com/page?id=1");
    }

    @Test
    void isIdempotent() {
        URI once = normalizer.normalize(URI.create("HTTPS://Example.COM:443/a/../b#frag"));
        URI twice = normalizer.normalize(once);
        assertThat(twice).isEqualTo(once);
    }
}
