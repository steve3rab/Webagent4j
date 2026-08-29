package io.webagent4j.action.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import io.webagent4j.action.ActionExecutionMode;
import io.webagent4j.action.ActionFailureType;
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
import io.webagent4j.action.policy.IActionPolicy;
import io.webagent4j.common.RetryPolicy;
import io.webagent4j.dom.BoundingBox;
import io.webagent4j.dom.ElementState;
import io.webagent4j.dom.IElement;
import io.webagent4j.locator.api.ElementRole;
import io.webagent4j.policy.PolicyDecision;
import io.webagent4j.verification.VerificationEngine;
import io.webagent4j.wait.IMonotonicClock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongUnaryOperator;
import org.junit.jupiter.api.Test;

/**
 * Proves the second half of Invariant G8: exact-target verification itself can consume real time
 * (or observe a caller-visible interrupt), so the budget/interrupt state proven valid immediately
 * before calling {@link IElement#verifiedForExecution()} is not automatically still valid once it
 * returns. A backend must never be invoked on a budget that expired, or a thread that was
 * interrupted, while verification itself was running - exactly as closed as if that expiry or
 * interrupt had been observed before verification ever started.
 *
 * <p>Runs entirely on fake, manually-advanced monotonic time via {@link ActionExecutor}'s
 * package-private fake-clock constructor, so "verification consumed almost the entire deadline" is
 * a deterministic, single-step clock advance rather than a real sleep - no fragile wall-clock
 * timing assumption anywhere in this test.
 */
class TargetVerificationDeadlineBoundaryTest {

    @Test
    void aVerificationThatConsumesTheEntireRemainingBudgetPreventsTheBackendFromEverRunning() {
        AtomicLong fakeNanos = new AtomicLong();
        IMonotonicClock clock = fakeNanos::get;
        Duration timeout = Duration.ofMillis(100);
        IActionBackend backend = mock(IActionBackend.class);
        // Verification is proven and legitimate (returns the same target) - isolating this test
        // to the deadline boundary alone, never conflating it with a verification failure.
        DeadlineConsumingElement target =
                new DeadlineConsumingElement(fakeNanos, value -> value + timeout.toNanos() + 1);

        ActionResult<Void> result =
                execute(clock, timeout, backend, target, ctx -> PolicyDecision.allow("allowed"));

        assertThat(result.success()).isFalse();
        assertThat(result.executionMode()).isEqualTo(ActionExecutionMode.NOT_EXECUTED);
        assertThat(result.status()).isEqualTo(ActionStatus.TIMEOUT);
        assertThat(result.failure().orElseThrow().type()).isEqualTo(ActionFailureType.TIMEOUT);
        verify(backend, never()).click(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void anInterruptObservedDuringVerificationPreventsTheBackendFromEverRunning() {
        AtomicLong fakeNanos = new AtomicLong();
        IMonotonicClock clock = fakeNanos::get;
        Duration timeout = Duration.ofMillis(100);
        IActionBackend backend = mock(IActionBackend.class);
        DeadlineConsumingElement target =
                new DeadlineConsumingElement(
                        fakeNanos,
                        value -> {
                            Thread.currentThread().interrupt();
                            return value;
                        });

        ActionResult<Void> result;
        try {
            result =
                    execute(
                            clock,
                            timeout,
                            backend,
                            target,
                            ctx -> PolicyDecision.allow("allowed"));
        } finally {
            // Clears the flag this test itself set, so it never leaks into a later test on the
            // same thread.
            Thread.interrupted();
        }

        assertThat(result.success()).isFalse();
        assertThat(result.executionMode()).isEqualTo(ActionExecutionMode.NOT_EXECUTED);
        assertThat(result.status()).isEqualTo(ActionStatus.CANCELLED);
        assertThat(result.failure().orElseThrow().type()).isEqualTo(ActionFailureType.INTERRUPTED);
        verify(backend, never()).click(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void verificationThatConsumesOnlyASliverOfTheBudgetStillExecutesNormally() {
        // Negative control: a verification that consumes real but small time must not itself be
        // treated as a deadline overrun - proving the check above triggers on actual budget
        // exhaustion, not merely on "any time passed during verification."
        AtomicLong fakeNanos = new AtomicLong();
        IMonotonicClock clock = fakeNanos::get;
        Duration timeout = Duration.ofMillis(100);
        IActionBackend backend = mock(IActionBackend.class);
        DeadlineConsumingElement target =
                new DeadlineConsumingElement(fakeNanos, value -> value + 1);

        ActionResult<Void> result =
                execute(clock, timeout, backend, target, ctx -> PolicyDecision.allow("allowed"));

        assertThat(result.success()).isTrue();
        assertThat(result.executionMode()).isEqualTo(ActionExecutionMode.REAL);
        verify(backend, org.mockito.Mockito.times(1)).click(org.mockito.ArgumentMatchers.any());
    }

    private static ActionResult<Void> execute(
            IMonotonicClock clock,
            Duration timeout,
            IActionBackend backend,
            IElement target,
            IActionPolicy policy) {
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
                        null,
                        Optional.empty());
        ActionExecutionConfig config =
                new ActionExecutionConfig(
                        new ActionOptions(
                                timeout,
                                Duration.ofMillis(10),
                                RetryPolicy.defaults(),
                                ObservationCapturePolicy.NONE),
                        List.of(),
                        List.of(),
                        (context, remaining) ->
                                new StabilizationResult(true, Duration.ZERO, "settled"),
                        false,
                        false,
                        Optional.of(policy),
                        Optional.empty());

        return new ActionExecutor(clock, new VerificationEngine())
                .execute(context(backend), command, config);
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

    /**
     * A real, non-mock {@link IElement} whose {@code verifiedForExecution()} advances a shared fake
     * clock by an injectable amount before returning itself as the proven-identical target -
     * simulating a verification implementation whose cost is real (a remote check, an expensive DOM
     * walk) without ever depending on wall-clock time in the test itself.
     */
    private static final class DeadlineConsumingElement implements IElement {
        private final AtomicLong fakeNanos;
        private final LongUnaryOperator advanceNanos;

        DeadlineConsumingElement(AtomicLong fakeNanos, LongUnaryOperator advanceNanos) {
            this.fakeNanos = fakeNanos;
            this.advanceNanos = advanceNanos;
        }

        @Override
        public Optional<IElement> verifiedForExecution() {
            fakeNanos.set(advanceNanos.applyAsLong(fakeNanos.get()));
            return Optional.of(this);
        }

        @Override
        public ElementRole role() {
            return ElementRole.BUTTON;
        }

        @Override
        public String accessibleName() {
            return "Target";
        }

        @Override
        public String text() {
            return "";
        }

        @Override
        public String tagName() {
            return "button";
        }

        @Override
        public Map<String, String> attributes() {
            return Map.of();
        }

        @Override
        public boolean visible() {
            return true;
        }

        @Override
        public boolean enabled() {
            return true;
        }

        @Override
        public ElementState state() {
            return new ElementState(
                    true, true, true, false, false, false, false, false, true, true, false, true);
        }

        @Override
        public Optional<BoundingBox> boundingBox() {
            return Optional.empty();
        }

        @Override
        public void click() {}
    }
}
