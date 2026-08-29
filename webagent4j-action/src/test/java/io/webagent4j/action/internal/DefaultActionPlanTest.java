package io.webagent4j.action.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import io.webagent4j.action.ActionDiagnostics;
import io.webagent4j.action.ActionEvent;
import io.webagent4j.action.ActionExecutionMode;
import io.webagent4j.action.ActionFailure;
import io.webagent4j.action.ActionFailureType;
import io.webagent4j.action.ActionId;
import io.webagent4j.action.ActionIdempotency;
import io.webagent4j.action.ActionPlanStatus;
import io.webagent4j.action.ActionResult;
import io.webagent4j.action.ActionSideEffect;
import io.webagent4j.action.ActionStatus;
import io.webagent4j.action.ActionTimings;
import io.webagent4j.action.ActionType;
import io.webagent4j.action.IActionPlan;
import io.webagent4j.verification.VerificationResult;
import io.webagent4j.verification.VerificationType;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Exercises {@link DefaultActionPlan} directly - the sole {@link IActionPlan} implementation - to
 * prove its validation invariants and single-use {@code execute()} delegation. Package-private
 * construction means this test lives alongside it in {@code .internal} rather than in the public
 * {@code io.webagent4j.action} package.
 */
class DefaultActionPlanTest {

    @Test
    void aReadyPlanCannotCarryAFailure() {
        assertThatIllegalArgumentException()
                .isThrownBy(
                        () ->
                                plan(
                                        ActionPlanStatus.READY,
                                        Optional.of(
                                                new ActionFailure(
                                                        ActionFailureType.TARGET_NOT_FOUND,
                                                        "message",
                                                        Optional.empty())),
                                        () -> success()));
    }

    @Test
    void aBlockedPlanMustCarryAFailure() {
        assertThatIllegalArgumentException()
                .isThrownBy(
                        () -> plan(ActionPlanStatus.BLOCKED, Optional.empty(), () -> success()));
    }

    @Test
    void readyReflectsTheStoredStatus() {
        assertThat(plan(ActionPlanStatus.READY, Optional.empty(), () -> success()).ready())
                .isTrue();
        assertThat(
                        plan(
                                        ActionPlanStatus.BLOCKED,
                                        Optional.of(
                                                new ActionFailure(
                                                        ActionFailureType.TARGET_NOT_FOUND,
                                                        "message",
                                                        Optional.empty())),
                                        () -> success())
                                .ready())
                .isFalse();
    }

    @Test
    void executeDelegatesToTheSuppliedCallbackExactlyOnce() {
        AtomicInteger invocations = new AtomicInteger();
        IActionPlan<Void> plan =
                plan(
                        ActionPlanStatus.READY,
                        Optional.empty(),
                        () -> {
                            invocations.incrementAndGet();
                            return success();
                        });

        ActionResult<Void> result = plan.execute();

        assertThat(result.success()).isTrue();
        assertThat(invocations.get()).isEqualTo(1);
    }

    private static IActionPlan<Void> plan(
            ActionPlanStatus status,
            Optional<ActionFailure> failure,
            java.util.function.Supplier<ActionResult<Void>> executor) {
        return new DefaultActionPlan<>(
                ActionId.create(),
                ActionType.CLICK,
                ActionIdempotency.NON_IDEMPOTENT,
                ActionSideEffect.LOCAL_PAGE_STATE,
                status,
                "button 'Confirm'",
                List.of(),
                List.of(VerificationType.URL_CONTAINS),
                failure,
                ActionDiagnostics.empty(),
                List.of(),
                executor);
    }

    private static ActionResult<Void> success() {
        return new ActionResult<>(
                ActionId.create(),
                ActionType.CLICK,
                ActionExecutionMode.REAL,
                ActionStatus.SUCCESS,
                null,
                Duration.ZERO,
                ActionTimings.empty(Duration.ZERO),
                List.<VerificationResult>of(),
                List.<VerificationResult>of(),
                null,
                null,
                null,
                List.<ActionEvent>of(),
                Optional.empty(),
                ActionDiagnostics.empty());
    }
}
