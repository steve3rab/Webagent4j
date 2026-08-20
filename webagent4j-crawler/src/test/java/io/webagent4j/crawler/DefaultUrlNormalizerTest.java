package io.webagent4j.crawler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import io.webagent4j.crawler.api.QueryParameterPolicy;
import java.net.URI;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DefaultUrlNormalizerTest {

    private final DefaultUrlNormalizer normalizer =
            new DefaultUrlNormalizer(QueryParameterPolicy.keepAll());

    @Test
    void lowercasesSchemeAndHost() {
        URI normalized = normalizer.normalize(URI.create("HTTP://Example.TEST/path"));

        assertThat(normalized).isEqualTo(URI.create("http://example.test/path"));
    }

    @Test
    void dropsTheFragment() {
        URI normalized = normalizer.normalize(URI.create("https://example.test/page#section"));

        assertThat(normalized).isEqualTo(URI.create("https://example.test/page"));
    }

    @Test
    void dropsDefaultHttpPort() {
        URI normalized = normalizer.normalize(URI.create("http://example.test:80/path"));

        assertThat(normalized).isEqualTo(URI.create("http://example.test/path"));
    }

    @Test
    void dropsDefaultHttpsPort() {
        URI normalized = normalizer.normalize(URI.create("https://example.test:443/path"));

        assertThat(normalized).isEqualTo(URI.create("https://example.test/path"));
    }

    @Test
    void keepsNonDefaultPort() {
        URI normalized = normalizer.normalize(URI.create("http://example.test:8080/path"));

        assertThat(normalized).isEqualTo(URI.create("http://example.test:8080/path"));
    }

    @Test
    void resolvesDotSegments() {
        URI normalized = normalizer.normalize(URI.create("https://example.test/a/../products"));

        assertThat(normalized).isEqualTo(URI.create("https://example.test/products"));
    }

    @Test
    void mapsEmptyPathToRootSlash() {
        URI normalized = normalizer.normalize(URI.create("https://example.test"));

        assertThat(normalized).isEqualTo(URI.create("https://example.test/"));
    }

    @Test
    void neverCollapsesAnExplicitTrailingSlashOnANonEmptyPath() {
        URI normalized = normalizer.normalize(URI.create("https://example.test/products/"));

        assertThat(normalized).isEqualTo(URI.create("https://example.test/products/"));
    }

    @Test
    void dropsKnownTrackingParametersWhenPolicyRequests() {
        DefaultUrlNormalizer trackingAware =
                new DefaultUrlNormalizer(QueryParameterPolicy.dropKnownTracking());

        URI normalized =
                trackingAware.normalize(
                        URI.create("https://example.test/?id=1&utm_source=newsletter&ref=x"));

        assertThat(normalized.getRawQuery()).isEqualTo("id=1&ref=x");
    }

    @Test
    void neverReordersSurvivingQueryParameters() {
        URI normalized = normalizer.normalize(URI.create("https://example.test/?b=2&a=1&c=3"));

        assertThat(normalized.getRawQuery()).isEqualTo("b=2&a=1&c=3");
    }

    @Test
    void neverCorruptsAlreadyPercentEncodedPaths() {
        URI normalized = normalizer.normalize(URI.create("https://example.test/caf%C3%A9"));

        assertThat(normalized.getRawPath()).isEqualTo("/caf%C3%A9");
    }

    @Test
    void rejectsRelativeUris() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> normalizer.normalize(URI.create("/relative")));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "HTTP://Example.TEST:80/a/../products",
                "https://example.test/products/",
                "https://example.test/products?b=2&a=1",
                "https://example.test",
                "https://example.test/caf%C3%A9#section"
            })
    void normalizationIsIdempotent(String url) {
        URI once = normalizer.normalize(URI.create(url));
        URI twice = normalizer.normalize(once);

        assertThat(twice).isEqualTo(once);
    }
}
