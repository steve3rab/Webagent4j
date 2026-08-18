package io.webagent4j.wait;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/** Proves {@link WaitBudget}'s monotonic deadline arithmetic, including overflow saturation. */
class WaitBudgetTest {

    private final FakeMonotonicClock clock = new FakeMonotonicClock();

    @Test
    void remainingCountsDownAsTheClockAdvances() {
        WaitBudget budget = WaitBudget.start(Duration.ofSeconds(5), clock);

        assertThat(budget.remaining()).isEqualTo(Duration.ofSeconds(5));
        clock.advance(Duration.ofSeconds(2));
        assertThat(budget.remaining()).isEqualTo(Duration.ofSeconds(3));
        assertThat(budget.elapsed()).isEqualTo(Duration.ofSeconds(2));
        assertThat(budget.expired()).isFalse();
    }

    @Test
    void neverReportsNegativeRemainingOrElapsedPastTheDeadline() {
        WaitBudget budget = WaitBudget.start(Duration.ofMillis(100), clock);

        clock.advance(Duration.ofSeconds(10));

        assertThat(budget.remaining()).isEqualTo(Duration.ZERO);
        assertThat(budget.expired()).isTrue();
        assertThat(budget.elapsed()).isEqualTo(Duration.ofSeconds(10));
    }

    @Test
    void expiresExactlyAtTheDeadline() {
        WaitBudget budget = WaitBudget.start(Duration.ofMillis(100), clock);

        clock.advance(Duration.ofMillis(99));
        assertThat(budget.expired()).isFalse();
        clock.advance(Duration.ofMillis(1));
        assertThat(budget.expired()).isTrue();
    }

    @Test
    void saturatesInsteadOfOverflowingForAnImplausiblyLargeTimeout() {
        WaitBudget budget = WaitBudget.start(Duration.ofNanos(Long.MAX_VALUE - 10), clock);

        clock.advance(Duration.ofNanos(1_000));

        assertThat(budget.expired()).isFalse();
        assertThat(budget.remaining().isNegative()).isFalse();
    }

    @Test
    void rejectsANegativeTimeout() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> WaitBudget.start(Duration.ofMillis(-1), clock));
    }
}
