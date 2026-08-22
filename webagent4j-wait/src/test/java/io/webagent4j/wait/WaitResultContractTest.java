package io.webagent4j.wait;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class WaitResultContractTest {

    @Test
    void rejectsImpossibleSampleAndResultShapes() {
        assertThatThrownBy(
                        () ->
                                new WaitSample<String>(
                                        WaitSample.Status.SATISFIED,
                                        Optional.empty(),
                                        Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                new WaitResult<>(
                                        WaitStatus.SUCCESS,
                                        1,
                                        Duration.ZERO,
                                        Optional.empty(),
                                        Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                new WaitResult<>(
                                        WaitStatus.TIMED_OUT,
                                        1,
                                        Duration.ZERO,
                                        Optional.empty(),
                                        Optional.of(Duration.ZERO)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                new WaitResult<>(
                                        WaitStatus.SUCCESS,
                                        1,
                                        Duration.ofNanos(-1),
                                        Optional.of("value"),
                                        Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                new WaitResult<>(
                                        WaitStatus.SUCCESS,
                                        1,
                                        Duration.ZERO,
                                        Optional.of("value"),
                                        Optional.of(Duration.ofNanos(-1))))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
