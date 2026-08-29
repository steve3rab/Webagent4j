package io.webagent4j.action.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import io.webagent4j.action.ActionEvent;
import io.webagent4j.action.ActionExecutionMode;
import io.webagent4j.action.ActionFailureType;
import io.webagent4j.action.ActionIdempotency;
import io.webagent4j.action.ActionOptions;
import io.webagent4j.action.ActionResult;
import io.webagent4j.action.ActionSideEffect;
import io.webagent4j.action.ActionStage;
import io.webagent4j.action.ActionStatus;
import io.webagent4j.action.ActionType;
import io.webagent4j.action.IActionBackend;
import io.webagent4j.action.IActionContext;
import io.webagent4j.action.IStabilizationStrategy;
import io.webagent4j.action.ObservationCapturePolicy;
import io.webagent4j.action.StabilizationResult;
import io.webagent4j.common.RetryPolicy;
import io.webagent4j.dom.ElementState;
import io.webagent4j.dom.IElement;
import io.webagent4j.locator.api.ElementRole;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Proves {@link IStabilizationStrategy}'s result is never ignored: a backend side effect that has
 * already run must never be reported as {@link ActionStatus#SUCCESS} merely because nothing
 * explicitly threw, and it must always keep {@link ActionExecutionMode#REAL} - never {@code
 * NOT_EXECUTED} - regardless of what stabilization itself reports, since the backend already ran by
 * the time stabilization is ever consulted.
 */
class ActionStabilizationContractTest {

    @Test
    void unstableResultAfterARealSideEffectIsNeverReportedAsSuccess() {
        IActionBackend backend = mock(IActionBackend.class);
        ActionResult<Void> result =
                execute(
                        backend,
                        (context, remaining) ->
                                new StabilizationResult(false, Duration.ZERO, "never settled"));

        assertThat(result.success()).isFalse();
        assertThat(result.executionMode()).isEqualTo(ActionExecutionMode.REAL);
        assertThat(result.executed()).isTrue();
        assertThat(result.status()).isEqualTo(ActionStatus.EXECUTION_FAILED);
        assertThat(result.failure().orElseThrow().type())
                .isEqualTo(ActionFailureType.STABILIZATION_FAILED);
        verify(backend, times(1)).click(org.mockito.ArgumentMatchers.any());
        assertThat(stableCompletedEvents(result)).isEmpty();
    }

    @Test
    void aNullStabilizationResultFailsClosedRatherThanBeingTreatedAsStable() {
        IActionBackend backend = mock(IActionBackend.class);
        ActionResult<Void> result = execute(backend, (context, remaining) -> null);

        assertThat(result.success()).isFalse();
        assertThat(result.executionMode()).isEqualTo(ActionExecutionMode.REAL);
        assertThat(result.failure().orElseThrow().type())
                .isEqualTo(ActionFailureType.STABILIZATION_FAILED);
        verify(backend, times(1)).click(org.mockito.ArgumentMatchers.any());
        assertThat(stableCompletedEvents(result)).isEmpty();
    }

    @Test
    void stableResultAfterARealSideEffectFollowsTheNormalSuccessPath() {
        IActionBackend backend = mock(IActionBackend.class);
        ActionResult<Void> result =
                execute(
                        backend,
                        (context, remaining) ->
                                new StabilizationResult(true, Duration.ZERO, "settled"));

        assertThat(result.success()).isTrue();
        assertThat(result.executionMode()).isEqualTo(ActionExecutionMode.REAL);
        verify(backend, times(1)).click(org.mockito.ArgumentMatchers.any());
        assertThat(stableCompletedEvents(result)).hasSize(1);
    }

    @Test
    void aStabilizationStrategyThatThrowsAfterARealSideEffectProducesAStructuredFailure() {
        IActionBackend backend = mock(IActionBackend.class);
        RuntimeException boom = new RuntimeException("stabilization backend unavailable");
        ActionResult<Void> result =
                execute(
                        backend,
                        (context, remaining) -> {
                            throw boom;
                        });

        assertThat(result.success()).isFalse();
        assertThat(result.executionMode()).isEqualTo(ActionExecutionMode.REAL);
        assertThat(result.executed()).isTrue();
        assertThat(result.failure().orElseThrow().type())
                .isEqualTo(ActionFailureType.STABILIZATION_FAILED);
        // Never a second, retried attempt at the side effect - the backend was already invoked
        // once, and stabilization failing afterward must never trigger another invocation.
        verify(backend, times(1)).click(org.mockito.ArgumentMatchers.any());
        assertThat(stableCompletedEvents(result)).isEmpty();
    }

    @Test
    void aStabilizationExceptionCarryingASecretNeverLeaksThroughAnySafeRenderer() {
        IActionBackend backend = mock(IActionBackend.class);
        String secretMarker = "WEBAGENT4J_STABILIZATION_EXCEPTION_SECRET";
        ActionResult<Void> result =
                execute(
                        backend,
                        (context, remaining) -> {
                            throw new RuntimeException(
                                    "stabilization backend unavailable: password=" + secretMarker);
                        });

        assertThat(result.failure().orElseThrow().type())
                .isEqualTo(ActionFailureType.STABILIZATION_FAILED);
        assertThat(result.toCompactText()).doesNotContain(secretMarker);
        assertThat(result.decisionTrace().toString()).doesNotContain(secretMarker);
        assertThat(result.diagnostics().toString()).doesNotContain(secretMarker);
        for (ActionEvent event : result.events()) {
            assertThat(event.toString()).doesNotContain(secretMarker);
        }
        // The raw cause remains available in-process for a caller who explicitly wants it - only
        // the safe-by-default renderers above must never surface it.
        assertThat(result.failure().orElseThrow().cause().orElseThrow().getMessage())
                .contains(secretMarker);
    }

    private static List<ActionEvent> stableCompletedEvents(ActionResult<Void> result) {
        return result.events().stream()
                .filter(event -> event.stage() == ActionStage.STABILIZATION_COMPLETED)
                .toList();
    }

    private static ActionResult<Void> execute(
            IActionBackend backend, IStabilizationStrategy stabilization) {
        IElement target = element();
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
                                Duration.ofSeconds(5),
                                Duration.ofMillis(10),
                                RetryPolicy.defaults(),
                                ObservationCapturePolicy.NONE),
                        List.of(),
                        List.of(),
                        stabilization,
                        false,
                        false,
                        Optional.empty(),
                        Optional.empty());

        return new ActionExecutor().execute(context(backend), command, config);
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

    private static IElement element() {
        IElement element = mock(IElement.class);
        org.mockito.Mockito.when(element.role()).thenReturn(ElementRole.BUTTON);
        org.mockito.Mockito.when(element.accessibleName()).thenReturn("Target");
        org.mockito.Mockito.when(element.state())
                .thenReturn(
                        new ElementState(
                                true, true, true, false, false, false, false, false, true, true,
                                false, true));
        return element;
    }
}
