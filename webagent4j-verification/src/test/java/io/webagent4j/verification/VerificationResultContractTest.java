package io.webagent4j.verification;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class VerificationResultContractTest {

    @Test
    void rejectsSuccessfulTimedOutAndNegativeDurationResults() {
        assertThatThrownBy(
                        () ->
                                new VerificationResult(
                                        true,
                                        VerificationType.CUSTOM,
                                        "ready",
                                        "ready",
                                        "ready",
                                        Duration.ZERO,
                                        true))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                new VerificationResult(
                                        false,
                                        VerificationType.CUSTOM,
                                        "ready",
                                        "ready",
                                        "pending",
                                        Duration.ofNanos(-1),
                                        false))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
