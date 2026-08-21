package io.webagent4j.browser;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ConditionTimeoutExceptionTest {

    @Test
    void messageOnlyConstructorCarriesNoCause() {
        ConditionTimeoutException exception = new ConditionTimeoutException("not stable");

        assertThat(exception.getMessage()).isEqualTo("not stable");
        assertThat(exception.getCause()).isNull();
    }

    @Test
    void messageAndCauseConstructorPreservesTheOriginalFailure() {
        RuntimeException cause = new RuntimeException("native timeout");

        ConditionTimeoutException exception = new ConditionTimeoutException("not stable", cause);

        assertThat(exception.getMessage()).isEqualTo("not stable");
        assertThat(exception.getCause()).isSameAs(cause);
    }
}
