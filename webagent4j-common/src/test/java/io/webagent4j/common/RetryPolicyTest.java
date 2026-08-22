package io.webagent4j.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class RetryPolicyTest {

    @Test
    void capsExponentialDelay() {
        RetryPolicy policy =
                new RetryPolicy(5, Duration.ofMillis(100), 3.0, Duration.ofMillis(500));

        assertThat(policy.delayBeforeAttempt(2)).isEqualTo(Duration.ofMillis(100));
        assertThat(policy.delayBeforeAttempt(3)).isEqualTo(Duration.ofMillis(300));
        assertThat(policy.delayBeforeAttempt(4)).isEqualTo(Duration.ofMillis(500));
    }

    @Test
    void rejectsAnEmptyAttemptBudget() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new RetryPolicy(0, Duration.ZERO, 1.0, Duration.ofSeconds(1)));
    }

    @Test
    void exposesDefaultsAndResultBasedRetryDecisions() {
        RetryPolicy policy = RetryPolicy.defaults();

        assertThat(policy.maxAttempts()).isEqualTo(3);
        assertThat(policy.shouldRetry(1, "retry", "retry"::equals)).isTrue();
        assertThat(policy.shouldRetry(3, "retry", "retry"::equals)).isFalse();
        assertThatIllegalArgumentException().isThrownBy(() -> policy.delayBeforeAttempt(1));
        assertThatNullPointerException().isThrownBy(() -> policy.shouldRetry(1, "value", null));
    }

    @Test
    void validatesRetryAndTimeoutInvariants() {
        assertThatIllegalArgumentException()
                .isThrownBy(
                        () -> new RetryPolicy(1, Duration.ofMillis(-1), 1.0, Duration.ofMillis(1)));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new RetryPolicy(1, Duration.ZERO, 0.5, Duration.ofMillis(1)));
        assertThatIllegalArgumentException()
                .isThrownBy(
                        () -> new RetryPolicy(1, Duration.ZERO, Double.NaN, Duration.ofMillis(1)));
        assertThatIllegalArgumentException()
                .isThrownBy(
                        () ->
                                new RetryPolicy(
                                        1,
                                        Duration.ZERO,
                                        Double.POSITIVE_INFINITY,
                                        Duration.ofMillis(1)));
        assertThat(Timeouts.defaults().navigation()).isEqualTo(Duration.ofSeconds(30));
        assertThatIllegalArgumentException()
                .isThrownBy(
                        () ->
                                new Timeouts(
                                        Duration.ZERO,
                                        Duration.ofSeconds(1),
                                        Duration.ofSeconds(1),
                                        Duration.ofSeconds(1)));
    }

    @Test
    void saturatesDelayArithmeticWhenDurationsOrExponentialGrowthExceedMillis() {
        RetryPolicy hugeDuration =
                new RetryPolicy(
                        3,
                        Duration.ofSeconds(Long.MAX_VALUE),
                        2.0,
                        Duration.ofSeconds(Long.MAX_VALUE));
        RetryPolicy hugeGrowth =
                new RetryPolicy(100, Duration.ofMillis(2), Double.MAX_VALUE, Duration.ofSeconds(3));
        RetryPolicy zeroDelay =
                new RetryPolicy(100, Duration.ZERO, Double.MAX_VALUE, Duration.ofSeconds(3));

        assertThat(hugeDuration.delayBeforeAttempt(2)).isEqualTo(Duration.ofMillis(Long.MAX_VALUE));
        assertThat(hugeDuration.delayBeforeAttempt(3)).isEqualTo(Duration.ofMillis(Long.MAX_VALUE));
        assertThat(hugeGrowth.delayBeforeAttempt(100)).isEqualTo(Duration.ofSeconds(3));
        assertThat(zeroDelay.delayBeforeAttempt(100)).isZero();
    }

    @Test
    void preservesExceptionDiagnostics() {
        IllegalStateException cause = new IllegalStateException("cause");

        assertThat(new WebAgentException("message").getMessage()).isEqualTo("message");
        assertThat(new WebAgentException("message", cause).getCause()).isSameAs(cause);
        assertThat(new BrowserException("browser", cause).getCause()).isSameAs(cause);
        assertThat(new BrowserException("browser").getMessage()).isEqualTo("browser");
        assertThat(new LocatorException("locator").getMessage()).isEqualTo("locator");
    }
}
