package io.webagent4j.crawler.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.api.Test;

class HttpResponseClassifierTest {

    @Test
    void classifiesSuccess() {
        assertThat(HttpResponseClassifier.isSuccess(200)).isTrue();
        assertThat(HttpResponseClassifier.isSuccess(299)).isTrue();
        assertThat(HttpResponseClassifier.isSuccess(300)).isFalse();
        assertThat(HttpResponseClassifier.isSuccess(199)).isFalse();
    }

    @Test
    void classifiesRedirects() {
        assertThat(HttpResponseClassifier.isRedirect(301)).isTrue();
        assertThat(HttpResponseClassifier.isRedirect(302)).isTrue();
        assertThat(HttpResponseClassifier.isRedirect(303)).isTrue();
        assertThat(HttpResponseClassifier.isRedirect(307)).isTrue();
        assertThat(HttpResponseClassifier.isRedirect(308)).isTrue();
        assertThat(HttpResponseClassifier.isRedirect(304)).isFalse();
    }

    @Test
    void classifiesClientErrors() {
        assertThat(HttpResponseClassifier.isClientError(404)).isTrue();
        assertThat(HttpResponseClassifier.isClientError(400)).isTrue();
        assertThat(HttpResponseClassifier.isClientError(499)).isTrue();
        assertThat(HttpResponseClassifier.isClientError(500)).isFalse();
    }

    @Test
    void classifiesServerErrors() {
        assertThat(HttpResponseClassifier.isServerError(500)).isTrue();
        assertThat(HttpResponseClassifier.isServerError(503)).isTrue();
        assertThat(HttpResponseClassifier.isServerError(599)).isTrue();
        assertThat(HttpResponseClassifier.isServerError(499)).isFalse();
    }

    @Test
    void neverRetriesAStatusCodeNotExplicitlyConfigured() {
        assertThat(HttpResponseClassifier.isRetryable(404, Set.of(429, 500, 502, 503, 504)))
                .isFalse();
        assertThat(HttpResponseClassifier.isRetryable(503, Set.of(429, 500, 502, 503, 504)))
                .isTrue();
    }
}
