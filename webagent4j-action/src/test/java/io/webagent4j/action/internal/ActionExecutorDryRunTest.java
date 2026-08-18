package io.webagent4j.action.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.webagent4j.action.ActionExecutionMode;
import io.webagent4j.action.ActionFailureType;
import io.webagent4j.action.ActionResult;
import io.webagent4j.action.ActionStage;
import io.webagent4j.action.ActionStatus;
import io.webagent4j.action.IActionBackend;
import io.webagent4j.action.IActionContext;
import io.webagent4j.dom.ElementState;
import io.webagent4j.dom.IElement;
import io.webagent4j.locator.api.ElementRole;
import io.webagent4j.verification.IVerification;
import io.webagent4j.verification.IVerificationContext;
import io.webagent4j.verification.VerificationResult;
import io.webagent4j.verification.VerificationType;
import org.junit.jupiter.api.Test;

/**
 * Proves dryRun()'s contract: no backend invocation, no backend-stage events, exactly one terminal
 * event, and postconditions that depend on the side effect are never evaluated.
 */
class ActionExecutorDryRunTest {

    @Test
    void neverInvokesTheBackendAndEmitsExactlyOneTerminalEvent() {
        IActionBackend backend = mock(IActionBackend.class);
        IElement target = actionableElement();

        ActionResult<Void> result =
                new DefaultActionBuilder(context(backend)).click(target).dryRun().execute();

        assertThat(result.success()).isTrue();
        assertThat(result.dryRun()).isTrue();
        assertThat(result.executed()).isFalse();
        assertThat(result.executionMode()).isEqualTo(ActionExecutionMode.DRY_RUN);

        verify(backend, never()).click(target);
        assertThat(result.events())
                .extracting("stage")
                .doesNotContain(
                        ActionStage.BACKEND_ACTION_STARTED,
                        ActionStage.BACKEND_ACTION_COMPLETED,
                        ActionStage.STABILIZATION_STARTED,
                        ActionStage.VERIFICATION_STARTED);
        assertThat(result.events())
                .filteredOn(event -> event.stage() == ActionStage.ACTION_COMPLETED)
                .hasSize(1);
    }

    @Test
    void aFailedPreconditionBlocksBeforeDryRunIsEvenConsidered() {
        IActionBackend backend = mock(IActionBackend.class);
        IElement disabled = element(false);

        ActionResult<Void> result =
                new DefaultActionBuilder(context(backend)).click(disabled).dryRun().execute();

        assertThat(result.success()).isFalse();
        assertThat(result.failure().orElseThrow().type())
                .isEqualTo(ActionFailureType.PRECONDITION_FAILED);
        assertThat(result.status()).isEqualTo(ActionStatus.PRECONDITION_FAILED);
        assertThat(result.executionMode()).isEqualTo(ActionExecutionMode.NOT_EXECUTED);
        assertThat(result.dryRun()).isFalse();
        verifyNoInteractions(backend);
    }

    @Test
    void neverEvaluatesAPostconditionThatDependsOnTheSideEffect() {
        IActionBackend backend = mock(IActionBackend.class);
        IElement target = actionableElement();
        IVerification neverCalled =
                new IVerification() {
                    @Override
                    public VerificationType type() {
                        return VerificationType.CUSTOM;
                    }

                    @Override
                    public VerificationResult verify(IVerificationContext context) {
                        throw new AssertionError(
                                "a dry-run must never evaluate a side-effect-dependent postcondition");
                    }
                };

        ActionResult<Void> result =
                new DefaultActionBuilder(context(backend))
                        .click(target)
                        .expect(neverCalled)
                        .dryRun()
                        .execute();

        assertThat(result.success()).isTrue();
        assertThat(result.postconditions()).isEmpty();
        verifyNoInteractions(backend);
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
        return element;
    }
}
