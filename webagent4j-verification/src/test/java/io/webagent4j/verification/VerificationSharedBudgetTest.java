package io.webagent4j.verification;

import static org.assertj.core.api.Assertions.assertThat;

import io.webagent4j.wait.IMonotonicClock;
import io.webagent4j.wait.WaitBudget;
import io.webagent4j.wait.WaitEngine;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/**
 * Proves that {@link VerificationEngine#awaitAll(IVerificationContext, List, WaitBudget, Duration)}
 * shares one deadline across every condition instead of - like the still-supported fixed-{@link
 * Duration} overload - giving each condition an independent full timeout.
 */
class VerificationSharedBudgetTest {

    private final IVerificationContext context =
            new IVerificationContext() {
                @Override
                public String url() {
                    return "https://local.test/checkout";
                }

                @Override
                public String title() {
                    return "Checkout";
                }
            };

    @Test
    void laterConditionsReceiveOnlyWhatIsLeftOfTheSharedBudget() {
        AtomicLong fakeNanos = new AtomicLong();
        IMonotonicClock clock = fakeNanos::get;
        // A no-op sleeper that still advances the clock by the requested amount, so the engine's
        // real interval/deadline arithmetic runs, without a real test ever actually sleeping.
        VerificationPoller poller =
                new VerificationPoller(
                        new WaitEngine(clock, duration -> fakeNanos.addAndGet(duration.toNanos())));
        VerificationEngine engine = new VerificationEngine(poller);

        // First condition never succeeds: it will consume its entire share of the budget.
        IVerification neverSucceeds = current -> new VerificationResult(false, "first", "pending");
        // Second condition needs three evaluations to succeed - trivially achievable with its own
        // fresh 100ms budget, but not with whatever is left after the first condition starved it.
        AtomicLong secondEvaluations = new AtomicLong();
        IVerification succeedsOnThirdEvaluation =
                current ->
                        new VerificationResult(
                                secondEvaluations.incrementAndGet() >= 3, "second", "state");

        WaitBudget budget = WaitBudget.start(Duration.ofMillis(100), clock);
        List<VerificationResult> results =
                engine.awaitAll(
                        context,
                        List.of(neverSucceeds, succeedsOnThirdEvaluation),
                        budget,
                        Duration.ofMillis(10));

        assertThat(results.get(0).success()).isFalse();
        assertThat(results.get(0).timedOut()).isTrue();
        // The whole 100ms budget was spent on the first condition, so the second condition is
        // handed an already-exhausted budget: it never reaches its third, successful evaluation.
        assertThat(secondEvaluations.get()).isLessThan(3);
        assertThat(results.get(1).success()).isFalse();
    }

    @Test
    void theFixedDurationOverloadStillGivesEachConditionAnIndependentTimeout() {
        VerificationPoller poller = new VerificationPoller();
        VerificationEngine engine = new VerificationEngine(poller);
        IVerification succeedsImmediately = current -> new VerificationResult(true, "ok", "ready");

        List<VerificationResult> results =
                engine.awaitAll(
                        context,
                        List.of(succeedsImmediately, succeedsImmediately),
                        Duration.ofMillis(50),
                        Duration.ofMillis(5));

        assertThat(results).allSatisfy(result -> assertThat(result.success()).isTrue());
    }
}
