package io.webagent4j.action.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.webagent4j.action.ActionExecutionMode;
import io.webagent4j.action.ActionFailureType;
import io.webagent4j.action.ActionPlanStatus;
import io.webagent4j.action.ActionResult;
import io.webagent4j.action.ActionStatus;
import io.webagent4j.action.IActionBackend;
import io.webagent4j.action.IActionContext;
import io.webagent4j.action.IActionPlan;
import io.webagent4j.action.IPreparedAction;
import io.webagent4j.action.policy.ActionPolicyContext;
import io.webagent4j.action.policy.ActionPolicyMode;
import io.webagent4j.action.policy.IActionPolicy;
import io.webagent4j.dom.ElementState;
import io.webagent4j.dom.IElement;
import io.webagent4j.locator.api.ElementRole;
import io.webagent4j.policy.PolicyDecision;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Proves the action-authorization gate's core safety invariants: a policy must {@code ALLOW} before
 * the backend is ever invoked, an evaluation failure (deny, exception, or a malformed {@code null}
 * decision) fails closed with zero backend invocations, and the gate applies identically whether
 * the action is executed directly, as a dry run, or through a plan.
 */
class ActionAuthorizationTest {

    @Test
    void deniedPolicyNeverInvokesBackend() {
        IActionBackend backend = mock(IActionBackend.class);
        IElement target = actionableElement();
        IActionPolicy denyAll = context -> PolicyDecision.deny("test.denied");

        ActionResult<Void> result =
                new DefaultActionBuilder(context(backend)).click(target).policy(denyAll).execute();

        assertThat(result.success()).isFalse();
        assertThat(result.status()).isEqualTo(ActionStatus.EXECUTION_FAILED);
        assertThat(result.executionMode()).isEqualTo(ActionExecutionMode.NOT_EXECUTED);
        assertThat(result.executed()).isFalse();
        assertThat(result.failure().orElseThrow().type())
                .isEqualTo(ActionFailureType.POLICY_DENIED);
        verifyNoInteractions(backend);
    }

    @Test
    void allowedPolicyPermitsExactlyOneBackendInvocation() {
        IActionBackend backend = mock(IActionBackend.class);
        IElement target = actionableElement();
        IActionPolicy allowAll = context -> PolicyDecision.allow("test.allowed");

        ActionResult<Void> result =
                new DefaultActionBuilder(context(backend)).click(target).policy(allowAll).execute();

        assertThat(result.success()).isTrue();
        assertThat(result.executed()).isTrue();
        verify(backend, times(1)).click(target);
    }

    @Test
    void policyFailureFailsClosedBeforeBackend() {
        IActionBackend backend = mock(IActionBackend.class);
        IElement target = actionableElement();
        IActionPolicy throwing =
                context -> {
                    throw new RuntimeException("policy backend unreachable");
                };

        ActionResult<Void> result =
                new DefaultActionBuilder(context(backend)).click(target).policy(throwing).execute();

        assertThat(result.success()).isFalse();
        assertThat(result.executionMode()).isEqualTo(ActionExecutionMode.NOT_EXECUTED);
        assertThat(result.failure().orElseThrow().type())
                .isEqualTo(ActionFailureType.POLICY_EVALUATION_FAILED);
        verifyNoInteractions(backend);
    }

    @Test
    void nullPolicyDecisionFailsClosed() {
        IActionBackend backend = mock(IActionBackend.class);
        IElement target = actionableElement();
        IActionPolicy nullReturning = context -> null;

        ActionResult<Void> result =
                new DefaultActionBuilder(context(backend))
                        .click(target)
                        .policy(nullReturning)
                        .execute();

        assertThat(result.success()).isFalse();
        assertThat(result.executionMode()).isEqualTo(ActionExecutionMode.NOT_EXECUTED);
        assertThat(result.failure().orElseThrow().type())
                .isEqualTo(ActionFailureType.POLICY_EVALUATION_FAILED);
        verifyNoInteractions(backend);
    }

    @Test
    void precedingPreconditionFailureIsCheckedBeforePolicyIsEverConsulted() {
        IActionBackend backend = mock(IActionBackend.class);
        IElement disabled = element(false);
        AtomicInteger policyCalls = new AtomicInteger();
        IActionPolicy countingPolicy =
                context -> {
                    policyCalls.incrementAndGet();
                    return PolicyDecision.allow("test.allowed");
                };

        ActionResult<Void> result =
                new DefaultActionBuilder(context(backend))
                        .click(disabled)
                        .policy(countingPolicy)
                        .execute();

        assertThat(result.status()).isEqualTo(ActionStatus.PRECONDITION_FAILED);
        assertThat(policyCalls.get()).isZero();
        verifyNoInteractions(backend);
    }

    @Test
    void dryRunStillConsultsThePolicyAndReportsDryRunModeOnAllow() {
        IActionBackend backend = mock(IActionBackend.class);
        IElement target = actionableElement();
        List<ActionPolicyMode> observedModes = new ArrayList<>();
        IActionPolicy recordingPolicy =
                context -> {
                    observedModes.add(context.mode());
                    return PolicyDecision.allow("test.allowed");
                };

        ActionResult<Void> result =
                new DefaultActionBuilder(context(backend))
                        .click(target)
                        .policy(recordingPolicy)
                        .dryRun()
                        .execute();

        assertThat(result.success()).isTrue();
        assertThat(result.dryRun()).isTrue();
        assertThat(observedModes).containsExactly(ActionPolicyMode.DRY_RUN);
        verifyNoInteractions(backend);
    }

    @Test
    void dryRunDeniedByPolicyReportsFailureRatherThanADryRunSuccess() {
        IActionBackend backend = mock(IActionBackend.class);
        IElement target = actionableElement();
        IActionPolicy denyAll = context -> PolicyDecision.deny("test.denied");

        ActionResult<Void> result =
                new DefaultActionBuilder(context(backend))
                        .click(target)
                        .policy(denyAll)
                        .dryRun()
                        .execute();

        assertThat(result.success()).isFalse();
        assertThat(result.dryRun()).isFalse();
        assertThat(result.executionMode()).isEqualTo(ActionExecutionMode.NOT_EXECUTED);
        assertThat(result.failure().orElseThrow().type())
                .isEqualTo(ActionFailureType.POLICY_DENIED);
        verifyNoInteractions(backend);
    }

    @Test
    void planExecuteReevaluatesPolicyAndIsDeniedIndependentlyOfThePlanSnapshot() {
        IActionBackend backend = mock(IActionBackend.class);
        IElement target = actionableElement();
        IActionPolicy denyAll = context -> PolicyDecision.deny("test.denied");

        IActionPlan<Void> plan =
                new DefaultActionBuilder(context(backend)).click(target).policy(denyAll).plan();

        // The plan-time snapshot already saw the denial, so the plan itself is BLOCKED - but
        // execute() still re-evaluates fresh rather than trusting that snapshot, independently
        // reaching (and reporting) the same denial.
        assertThat(plan.status()).isEqualTo(ActionPlanStatus.BLOCKED);
        verifyNoInteractions(backend);

        ActionResult<Void> result = plan.execute();

        assertThat(result.success()).isFalse();
        assertThat(result.failure().orElseThrow().type())
                .isEqualTo(ActionFailureType.POLICY_DENIED);
        assertThat(result.actionId()).isEqualTo(plan.actionId());
        verifyNoInteractions(backend);
    }

    @Test
    void planExecuteInvokesBackendExactlyOnceWhenPolicyAllows() {
        IActionBackend backend = mock(IActionBackend.class);
        IElement target = actionableElement();
        // plan() evaluates once (PLAN mode, an informational snapshot - see policyDecisions()),
        // and execute() always re-evaluates fresh (EXECUTE mode) rather than trusting that
        // snapshot - so two evaluations total, never more, and the backend is still invoked
        // exactly once regardless.
        List<ActionPolicyMode> observedModes = new ArrayList<>();
        IActionPolicy recordingAllow =
                context -> {
                    observedModes.add(context.mode());
                    return PolicyDecision.allow("test.allowed");
                };

        IActionPlan<Void> plan =
                new DefaultActionBuilder(context(backend))
                        .click(target)
                        .policy(recordingAllow)
                        .plan();
        ActionResult<Void> result = plan.execute();

        assertThat(result.success()).isTrue();
        assertThat(observedModes).containsExactly(ActionPolicyMode.PLAN, ActionPolicyMode.EXECUTE);
        verify(backend, times(1)).click(target);
    }

    @Test
    void interruptObservedAfterPolicyStillPreventsBackendInvocation() {
        IActionBackend backend = mock(IActionBackend.class);
        IElement target = actionableElement();
        IActionPolicy interruptingAllow =
                context -> {
                    Thread.currentThread().interrupt();
                    return PolicyDecision.allow("test.allowed");
                };

        try {
            ActionResult<Void> result =
                    new DefaultActionBuilder(context(backend))
                            .click(target)
                            .policy(interruptingAllow)
                            .execute();

            assertThat(result.status()).isEqualTo(ActionStatus.CANCELLED);
            assertThat(result.executionMode()).isEqualTo(ActionExecutionMode.NOT_EXECUTED);
            assertThat(result.failure().orElseThrow().type())
                    .isEqualTo(ActionFailureType.INTERRUPTED);
            verifyNoInteractions(backend);
        } finally {
            Thread.interrupted(); // clear the interrupt flag so it never leaks into another test
        }
    }

    @Test
    void policyMethodRejectsNull() {
        IActionBackend backend = mock(IActionBackend.class);
        IPreparedAction<Void> prepared =
                new DefaultActionBuilder(context(backend)).click(actionableElement());

        assertThatThrownBy(() -> prepared.policy(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void policyMethodRejectsDuplicateConfiguration() {
        IActionBackend backend = mock(IActionBackend.class);
        IPreparedAction<Void> prepared =
                new DefaultActionBuilder(context(backend)).click(actionableElement());
        prepared.policy(context -> PolicyDecision.allow("first"));

        assertThatThrownBy(() -> prepared.policy(context -> PolicyDecision.allow("second")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void defaultInterfaceMethodThrowsForAnImplementationThatDoesNotOverrideIt() {
        IPreparedAction<Void> unsupported = new NonOverridingPreparedAction();

        assertThatThrownBy(() -> unsupported.policy(context -> PolicyDecision.allow("x")))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void policyContextCarriesActionTypeIdempotencySideEffectAndSafeTargetDescription() {
        IActionBackend backend = mock(IActionBackend.class);
        IElement target = actionableElement();
        List<ActionPolicyContext> observed = new ArrayList<>();
        IActionPolicy recordingPolicy =
                context -> {
                    observed.add(context);
                    return PolicyDecision.allow("test.allowed");
                };

        new DefaultActionBuilder(context(backend)).click(target).policy(recordingPolicy).execute();

        assertThat(observed).hasSize(1);
        ActionPolicyContext captured = observed.get(0);
        assertThat(captured.actionType()).isEqualTo(io.webagent4j.action.ActionType.CLICK);
        assertThat(captured.mode()).isEqualTo(ActionPolicyMode.EXECUTE);
        assertThat(captured.targetDescription()).contains("BUTTON").contains("Target");
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

    private static IElement actionableElement() {
        return element(true);
    }

    private static IElement element(boolean enabled) {
        IElement element = mock(IElement.class);
        when(element.role()).thenReturn(ElementRole.BUTTON);
        when(element.accessibleName()).thenReturn("Target");
        when(element.state())
                .thenReturn(
                        new ElementState(
                                true, true, enabled, false, false, false, false, false, true,
                                enabled, false, true));
        // A mock never inherits IElement's real default implementation, so it must be told
        // explicitly that it is still the same target - matching what a backend without physical
        // -node identity tracking would report through that default method.
        when(element.isStillTheOriginallyResolvedTarget()).thenReturn(true);
        when(element.verifiedForExecution()).thenReturn(Optional.of(element));
        return element;
    }

    /** Minimal {@link IPreparedAction} that relies entirely on default interface methods. */
    private static final class NonOverridingPreparedAction implements IPreparedAction<Void> {
        @Override
        public IPreparedAction<Void> precondition(java.util.function.Predicate<IElement> p) {
            return this;
        }

        @Override
        public IPreparedAction<Void> require(io.webagent4j.verification.IVerification v) {
            return this;
        }

        @Override
        public IPreparedAction<Void> expect(io.webagent4j.verification.IVerification v) {
            return this;
        }

        @Override
        public IPreparedAction<Void> expectUrlContains(String expectedFragment) {
            return this;
        }

        @Override
        public IPreparedAction<Void> timeout(java.time.Duration timeout) {
            return this;
        }

        @Override
        public IPreparedAction<Void> retry(io.webagent4j.common.RetryPolicy retryPolicy) {
            return this;
        }

        @Override
        public IPreparedAction<Void> captureObservations(
                io.webagent4j.action.ObservationCapturePolicy policy) {
            return this;
        }

        @Override
        public ActionResult<Void> execute() {
            throw new UnsupportedOperationException();
        }

        @Override
        public IPreparedAction<Void> dryRun() {
            return this;
        }

        @Override
        public IActionPlan<Void> plan() {
            throw new UnsupportedOperationException();
        }
    }
}
