package io.webagent4j.action.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import io.webagent4j.action.ActionExecutionMode;
import io.webagent4j.action.ActionFailureType;
import io.webagent4j.action.ActionIdempotency;
import io.webagent4j.action.ActionOptions;
import io.webagent4j.action.ActionResult;
import io.webagent4j.action.ActionSideEffect;
import io.webagent4j.action.ActionType;
import io.webagent4j.action.IActionBackend;
import io.webagent4j.action.IActionContext;
import io.webagent4j.action.IStabilizationStrategy;
import io.webagent4j.action.KeyPress;
import io.webagent4j.action.ObservationCapturePolicy;
import io.webagent4j.action.PortableKey;
import io.webagent4j.action.Selection;
import io.webagent4j.action.StabilizationResult;
import io.webagent4j.action.policy.IActionPolicy;
import io.webagent4j.common.RetryPolicy;
import io.webagent4j.dom.BoundingBox;
import io.webagent4j.dom.ElementState;
import io.webagent4j.dom.IElement;
import io.webagent4j.locator.api.ElementRole;
import io.webagent4j.policy.PolicyDecision;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * GAM-001 through GAM-040: proves the shared governed-execution pipeline in {@link ActionExecutor}
 * - policy authorization, exact-target revalidation, deadline/interruption boundaries, and
 * exactly-once backend invocation - behaves identically for every Governed Actions V2 target-bound
 * action ({@code click}, {@code fill}/{@code type}, {@code typeSequentially}, {@code select},
 * {@code check}, {@code uncheck}, {@code hover}, {@code press}), not merely for {@code click}.
 *
 * <p>This is the extracted-mechanism proof at the pipeline layer: every scenario below constructs a
 * raw {@link ActionCommand} carrying a different {@link ActionType} and a different {@link
 * IActionBackend} method reference, then runs it through the exact same {@link ActionExecutor}
 * unchanged - so a regression that special-cased {@code click} (or any other single action) would
 * fail every other row of this matrix.
 *
 * <p>Also proves action-type integrity (Section 11 of the Governed Actions V2 spec): the {@code
 * ActionType} a policy observes during evaluation is always exactly the type of the action actually
 * being executed - a policy authorizing {@code HOVER} can never accidentally see or authorize
 * {@code CLICK}.
 */
class GovernedActionTypeMatrixTest {

    @AfterEach
    void clearInterruptFlag() {
        Thread.interrupted();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void allowedActionWithProvenIdentityExecutesTheBackendExactlyOnceWithMatchingActionType(
            ActionCase actionCase) {
        IActionBackend backend = mock(IActionBackend.class);
        AtomicReference<ActionType> observedByPolicy = new AtomicReference<>();
        IActionPolicy policy =
                ctx -> {
                    observedByPolicy.set(ctx.actionType());
                    return PolicyDecision.allow("test.allowed");
                };

        ActionResult<Void> result = execute(backend, actionCase, policy, false, false, false);

        assertThat(result.success()).as(actionCase.label()).isTrue();
        assertThat(result.executionMode()).isEqualTo(ActionExecutionMode.REAL);
        assertThat(observedByPolicy.get()).isEqualTo(actionCase.type());
        actionCase.verifyInvokedExactlyOnce().accept(backend);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void policyDenialInvokesTheBackendZeroTimes(ActionCase actionCase) {
        IActionBackend backend = mock(IActionBackend.class);
        IActionPolicy policy = ctx -> PolicyDecision.deny("test.denied");

        ActionResult<Void> result = execute(backend, actionCase, policy, false, false, false);

        assertThat(result.success()).as(actionCase.label()).isFalse();
        assertThat(result.executionMode()).isEqualTo(ActionExecutionMode.NOT_EXECUTED);
        assertThat(result.failure().orElseThrow().type())
                .isEqualTo(ActionFailureType.POLICY_DENIED);
        verifyNoInteractions(backend);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void targetChangedBeforeBackendInvokesTheBackendZeroTimes(ActionCase actionCase) {
        IActionBackend backend = mock(IActionBackend.class);
        IActionPolicy policy = ctx -> PolicyDecision.allow("test.allowed");

        ActionResult<Void> result = execute(backend, actionCase, policy, true, false, false);

        assertThat(result.success()).as(actionCase.label()).isFalse();
        assertThat(result.executionMode()).isEqualTo(ActionExecutionMode.NOT_EXECUTED);
        assertThat(result.failure().orElseThrow().type())
                .isEqualTo(ActionFailureType.TARGET_CHANGED);
        verifyNoInteractions(backend);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void budgetExpiredBeforeBackendInvokesTheBackendZeroTimes(ActionCase actionCase) {
        IActionBackend backend = mock(IActionBackend.class);
        IActionPolicy policy = ctx -> PolicyDecision.allow("test.allowed");

        ActionResult<Void> result = execute(backend, actionCase, policy, false, true, false);

        assertThat(result.success()).as(actionCase.label()).isFalse();
        assertThat(result.executionMode()).isEqualTo(ActionExecutionMode.NOT_EXECUTED);
        assertThat(result.failure().orElseThrow().type()).isEqualTo(ActionFailureType.TIMEOUT);
        verifyNoInteractions(backend);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void interruptionDuringPolicyEvaluationInvokesTheBackendZeroTimes(ActionCase actionCase) {
        IActionBackend backend = mock(IActionBackend.class);
        IActionPolicy policy =
                ctx -> {
                    Thread.currentThread().interrupt();
                    return PolicyDecision.allow("test.allowed");
                };

        ActionResult<Void> result = execute(backend, actionCase, policy, false, false, true);

        assertThat(result.success()).as(actionCase.label()).isFalse();
        assertThat(result.executionMode()).isEqualTo(ActionExecutionMode.NOT_EXECUTED);
        assertThat(result.failure().orElseThrow().type()).isEqualTo(ActionFailureType.INTERRUPTED);
        verifyNoInteractions(backend);
    }

    private static ActionResult<Void> execute(
            IActionBackend backend,
            ActionCase actionCase,
            IActionPolicy policy,
            boolean targetChanged,
            boolean budgetExpired,
            boolean interruptExpected) {
        IElement resolvedTarget = enabledElement(targetChanged);
        ActionCommand<Void> command =
                new ActionCommand<>(
                        actionCase.type(),
                        ActionIdempotency.IDEMPOTENT,
                        ActionSideEffect.LOCAL_PAGE_STATE,
                        budgetExpired ? sleepyReference(resolvedTarget) : () -> resolvedTarget,
                        actionCase.operation(),
                        null,
                        Optional.empty());
        IStabilizationStrategy alwaysStable =
                (context, remaining) -> new StabilizationResult(true, Duration.ZERO, "settled");
        ActionExecutionConfig config =
                new ActionExecutionConfig(
                        new ActionOptions(
                                budgetExpired ? Duration.ofMillis(5) : Duration.ofSeconds(5),
                                Duration.ofMillis(10),
                                RetryPolicy.defaults(),
                                ObservationCapturePolicy.NONE),
                        java.util.List.of(),
                        java.util.List.of(),
                        alwaysStable,
                        false,
                        false,
                        Optional.of(policy),
                        Optional.empty());

        ActionResult<Void> result = new ActionExecutor().execute(context(backend), command, config);
        if (interruptExpected) {
            assertThat(Thread.currentThread().isInterrupted())
                    .as("interrupt flag preserved")
                    .isTrue();
        }
        return result;
    }

    private static io.webagent4j.locator.api.IElementReference<IElement> sleepyReference(
            IElement target) {
        return () -> {
            try {
                Thread.sleep(50);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            return target;
        };
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

    private static IElement enabledElement(boolean targetChanged) {
        IElement replacement = mock(IElement.class);
        IElement element = mock(IElement.class);
        org.mockito.Mockito.when(element.role()).thenReturn(ElementRole.BUTTON);
        org.mockito.Mockito.when(element.accessibleName()).thenReturn("Target");
        org.mockito.Mockito.when(element.state())
                .thenReturn(
                        // editable=true so TYPE/TYPE_SEQUENCE's editable precondition is satisfied
                        // too - every action case in this matrix must reach policy evaluation, not
                        // fail earlier on an action-specific precondition unrelated to what this
                        // test proves.
                        new ElementState(
                                true, true, true, true, false, false, false, false, true, true,
                                false, true));
        org.mockito.Mockito.when(element.boundingBox()).thenReturn(Optional.<BoundingBox>empty());
        org.mockito.Mockito.when(element.verifiedForExecution())
                .thenReturn(targetChanged ? Optional.empty() : Optional.of(element));
        return element;
    }

    private static Stream<Arguments> cases() {
        return Stream.of(
                        new ActionCase(
                                "click",
                                ActionType.CLICK,
                                (backend, target) -> {
                                    backend.click(target);
                                    return null;
                                },
                                backend -> verify(backend, times(1)).click(any())),
                        new ActionCase(
                                "type (fill)",
                                ActionType.TYPE,
                                (backend, target) -> {
                                    backend.fill(target, "hello");
                                    return null;
                                },
                                backend -> verify(backend, times(1)).fill(any(), anyString())),
                        new ActionCase(
                                "typeSequentially",
                                ActionType.TYPE_SEQUENCE,
                                (backend, target) -> {
                                    backend.typeSequentially(target, "hello");
                                    return null;
                                },
                                backend ->
                                        verify(backend, times(1))
                                                .typeSequentially(any(), anyString())),
                        new ActionCase(
                                "select",
                                ActionType.SELECT,
                                (backend, target) -> {
                                    backend.select(target, Selection.byValue("option-1"));
                                    return null;
                                },
                                backend ->
                                        verify(backend, times(1))
                                                .select(any(), any(Selection.class))),
                        new ActionCase(
                                "check",
                                ActionType.CHECK,
                                (backend, target) -> {
                                    backend.check(target);
                                    return null;
                                },
                                backend -> verify(backend, times(1)).check(any())),
                        new ActionCase(
                                "uncheck",
                                ActionType.UNCHECK,
                                (backend, target) -> {
                                    backend.uncheck(target);
                                    return null;
                                },
                                backend -> verify(backend, times(1)).uncheck(any())),
                        new ActionCase(
                                "hover",
                                ActionType.HOVER,
                                (backend, target) -> {
                                    backend.hover(target);
                                    return null;
                                },
                                backend -> verify(backend, times(1)).hover(any())),
                        new ActionCase(
                                "press",
                                ActionType.PRESS_KEY,
                                (backend, target) -> {
                                    backend.press(target, KeyPress.of(PortableKey.ENTER));
                                    return null;
                                },
                                backend ->
                                        verify(backend, times(1))
                                                .press(any(), any(KeyPress.class))))
                .map(Arguments::of);
    }

    /** One row of the action matrix: label, {@link ActionType}, backend operation, and proof. */
    private record ActionCase(
            String label,
            ActionType type,
            ITargetOperation<Void> operation,
            java.util.function.Consumer<IActionBackend> verifyInvokedExactlyOnce) {
        @Override
        public String toString() {
            return label;
        }
    }
}
