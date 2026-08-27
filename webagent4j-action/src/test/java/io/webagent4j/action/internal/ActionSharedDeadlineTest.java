package io.webagent4j.action.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import io.webagent4j.action.ActionIdempotency;
import io.webagent4j.action.ActionOptions;
import io.webagent4j.action.ActionResult;
import io.webagent4j.action.ActionSideEffect;
import io.webagent4j.action.ActionStatus;
import io.webagent4j.action.ActionType;
import io.webagent4j.action.IActionBackend;
import io.webagent4j.action.IActionContext;
import io.webagent4j.action.ObservationCapturePolicy;
import io.webagent4j.action.StabilizationResult;
import io.webagent4j.common.RetryPolicy;
import io.webagent4j.dom.ElementState;
import io.webagent4j.dom.IElement;
import io.webagent4j.locator.api.ElementRole;
import io.webagent4j.verification.IVerification;
import io.webagent4j.verification.VerificationEngine;
import io.webagent4j.verification.VerificationPoller;
import io.webagent4j.verification.VerificationResult;
import io.webagent4j.verification.VerificationType;
import io.webagent4j.wait.IMonotonicClock;
import io.webagent4j.wait.WaitEngine;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/**
 * Proves that an action's postconditions share one deadline instead of each independently receiving
 * a full, fresh timeout - the "3 conditions x 5s = 15s" bug this mission's Wait Engine migration
 * was meant to close (see {@code io.webagent4j.verification.VerificationSharedBudgetTest} for the
 * same proof one layer down, with fake time).
 *
 * <p>The proof runs entirely on fake, manually-advanced monotonic time: an {@link IMonotonicClock}
 * driven only by a substitute {@code IWaitSleeper} that advances it by exactly the requested sleep
 * duration instead of ever parking a real thread. This is the same deterministic technique {@code
 * VerificationSharedBudgetTest} uses one layer down, applied here through the real {@link
 * ActionExecutor} pipeline via its package-private fake-time constructor, so this test exercises
 * the exact production code path a future regression to per-postcondition budgets would break -
 * never {@code Instant.now()}, real elapsed wall-clock time, or a threshold tuned to survive
 * scheduler noise.
 */
class ActionSharedDeadlineTest {

    @Test
    void twoNeverSatisfiedPostconditionsTogetherConsumeOnlyOneActionTimeout() {
        AtomicLong fakeNanos = new AtomicLong();
        IMonotonicClock clock = fakeNanos::get;
        // A no-op sleeper that still advances the clock by the requested amount, so the engine's
        // real interval/deadline arithmetic runs, without a real test ever actually sleeping.
        WaitEngine waitEngine =
                new WaitEngine(clock, duration -> fakeNanos.addAndGet(duration.toNanos()));
        VerificationEngine verificationEngine =
                new VerificationEngine(new VerificationPoller(waitEngine));

        IElement target = element(true);
        AtomicLong secondEvaluations = new AtomicLong();
        IVerification neverSucceeds = neverSucceeds("first");
        IVerification countingNeverSucceeds =
                current -> {
                    secondEvaluations.incrementAndGet();
                    return new VerificationResult(
                            false,
                            VerificationType.CUSTOM,
                            "second",
                            "expected",
                            "actual",
                            Duration.ZERO,
                            false);
                };
        Duration timeout = Duration.ofMillis(80);
        Duration interval = Duration.ofMillis(10);

        IActionBackend backend = mock(IActionBackend.class);
        ActionCommand<Void> command =
                new ActionCommand<>(
                        ActionType.CLICK,
                        ActionIdempotency.NON_IDEMPOTENT,
                        ActionSideEffect.LOCAL_PAGE_STATE,
                        () -> target,
                        (actionBackend, resolvedTarget) -> {
                            actionBackend.click(resolvedTarget);
                            return null;
                        },
                        null);
        ActionExecutionConfig config =
                new ActionExecutionConfig(
                        new ActionOptions(
                                timeout,
                                interval,
                                RetryPolicy.defaults(),
                                ObservationCapturePolicy.NONE),
                        List.of(),
                        List.of(neverSucceeds, countingNeverSucceeds),
                        (context, remaining) -> StabilizationResult.none(),
                        false,
                        false,
                        java.util.Optional.empty());

        ActionResult<Void> result =
                new ActionExecutor(clock, verificationEngine)
                        .execute(context(backend), command, config);

        assertThat(result.status()).isEqualTo(ActionStatus.TIMEOUT);
        // Deterministic proof of the shared budget, not an approximation: the first condition
        // alone consumes the entire fake-time budget (nothing else in this pipeline advances the
        // clock), so the action's total logical duration is bounded by exactly one timeout, never
        // approaching two independent ones. A regression that gave each postcondition its own
        // fresh timeout would make this equal roughly 2 * timeout instead.
        assertThat(result.duration()).isLessThanOrEqualTo(timeout);
        assertThat(result.duration()).isLessThan(timeout.multipliedBy(2));
        // The engine's own contract guarantees every condition at least one immediate probe, even
        // against an already-expired budget - so the second condition is evaluated exactly once
        // and receives no time of its own. A regression that reset the budget per condition would
        // let this evaluate repeatedly (as many times as the first condition did) instead.
        assertThat(secondEvaluations.get()).isEqualTo(1);
    }

    private static IVerification neverSucceeds(String description) {
        return current ->
                new VerificationResult(
                        false,
                        VerificationType.CUSTOM,
                        description,
                        "satisfied",
                        "never",
                        Duration.ZERO,
                        false);
    }

    private static IActionContext context(IActionBackend backend) {
        return new IActionContext() {
            @Override
            public String url() {
                return "https://example.test";
            }

            @Override
            public String title() {
                return "Example";
            }

            @Override
            public IActionBackend actionBackend() {
                return backend;
            }
        };
    }

    private static IElement element(boolean enabled) {
        IElement element = mock(IElement.class);
        org.mockito.Mockito.when(element.role()).thenReturn(ElementRole.BUTTON);
        org.mockito.Mockito.when(element.accessibleName()).thenReturn("Target");
        org.mockito.Mockito.when(element.state())
                .thenReturn(
                        new ElementState(
                                true, true, enabled, false, false, false, false, false, true,
                                enabled, false, true));
        return element;
    }
}
