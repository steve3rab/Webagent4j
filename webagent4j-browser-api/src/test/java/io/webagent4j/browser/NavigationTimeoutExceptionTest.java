package io.webagent4j.browser;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class NavigationTimeoutExceptionTest {

    @Test
    void messageOnlyConstructorCarriesNoCause() {
        NavigationTimeoutException exception = new NavigationTimeoutException("timed out");

        assertThat(exception.getMessage()).isEqualTo("timed out");
        assertThat(exception.getCause()).isNull();
    }

    @Test
    void messageAndCauseConstructorPreservesTheOriginalFailure() {
        RuntimeException cause = new RuntimeException("native timeout");

        NavigationTimeoutException exception = new NavigationTimeoutException("timed out", cause);

        assertThat(exception.getMessage()).isEqualTo("timed out");
        assertThat(exception.getCause()).isSameAs(cause);
    }
}
