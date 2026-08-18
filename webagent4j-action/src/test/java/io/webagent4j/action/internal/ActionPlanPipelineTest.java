package io.webagent4j.action.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import io.webagent4j.action.ActionFailureType;
import io.webagent4j.action.ActionPlan;
import io.webagent4j.action.ActionPlanStatus;
import io.webagent4j.action.ActionResult;
import io.webagent4j.action.IActionBackend;
import io.webagent4j.action.IActionContext;
import io.webagent4j.action.Secret;
import io.webagent4j.dom.ElementState;
import io.webagent4j.dom.IElement;
import io.webagent4j.locator.AmbiguousLocatorException;
import io.webagent4j.locator.LocatorNotFoundException;
import io.webagent4j.locator.api.ElementRole;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/**
 * Proves {@link io.webagent4j.action.IPreparedAction#plan()}'s contract end to end through the
 * public action API: zero backend side effects while planning, a plan that resolves as READY only
 * when the target and every precondition are satisfied, and {@link ActionPlan#execute()} running
 * the backend exactly once.
 */
class ActionPlanPipelineTest {

    @Test
    void isReadyForAValidTargetAndNeverTouchesTheBackend() {
        IActionBackend backend = mock(IActionBackend.class);
        IElement target = element(true);

        ActionPlan<Void> plan = new DefaultActionBuilder(context(backend)).click(target).plan();

        assertThat(plan.status()).isEqualTo(ActionPlanStatus.READY);
        assertThat(plan.ready()).isTrue();
        assertThat(plan.failure()).isEmpty();
        verifyNoInteractions(backend);
    }

    @Test
    void isBlockedWithTargetNotFoundAndNeverTouchesTheBackend() {
        IActionBackend backend = mock(IActionBackend.class);

        ActionPlan<Void> plan =
                new DefaultActionBuilder(context(backend))
                        .click(
                                () -> {
                                    throw new LocatorNotFoundException("missing");
                                })
                        .plan();

        assertThat(plan.status()).isEqualTo(ActionPlanStatus.BLOCKED);
        assertThat(plan.failure().orElseThrow().type())
                .isEqualTo(ActionFailureType.TARGET_NOT_FOUND);
        verifyNoInteractions(backend);
    }

    @Test
    void isBlockedWithTargetAmbiguousAndNeverTouchesTheBackend() {
        IActionBackend backend = mock(IActionBackend.class);

        ActionPlan<Void> plan =
                new DefaultActionBuilder(context(backend))
                        .click(
                                () -> {
                                    throw new AmbiguousLocatorException("ambiguous");
                                })
                        .plan();

        assertThat(plan.status()).isEqualTo(ActionPlanStatus.BLOCKED);
        assertThat(plan.failure().orElseThrow().type())
                .isEqualTo(ActionFailureType.TARGET_AMBIGUOUS);
        verifyNoInteractions(backend);
    }

    @Test
    void isBlockedWithPreconditionFailedAndNeverTouchesTheBackend() {
        IActionBackend backend = mock(IActionBackend.class);
        IElement disabled = element(false);

        ActionPlan<Void> plan = new DefaultActionBuilder(context(backend)).click(disabled).plan();

        assertThat(plan.status()).isEqualTo(ActionPlanStatus.BLOCKED);
        assertThat(plan.failure().orElseThrow().type())
                .isEqualTo(ActionFailureType.PRECONDITION_FAILED);
        verifyNoInteractions(backend);
    }

    @Test
    void executingAReadyPlanRunsTheBackendExactlyOnce() {
        IActionBackend backend = mock(IActionBackend.class);
        IElement target = element(true);

        ActionPlan<Void> plan = new DefaultActionBuilder(context(backend)).click(target).plan();
        ActionResult<Void> result = plan.execute();

        assertThat(result.success()).isTrue();
        assertThat(result.executed()).isTrue();
        verify(backend, times(1)).click(target);
    }

    @Test
    void aBlockedPlanCanSucceedLaterIfTheBlockingConditionClears() {
        // WebAgent4J's revalidation model is dynamic: a plan is a snapshot of one moment, not a
        // promise about the future. A BLOCKED plan is therefore allowed to succeed once its target
        // actually appears, exactly like a READY plan is allowed to fail once its target disappears
        // - execute() always trusts current state, never the plan()-time snapshot, in either
        // direction.
        IActionBackend backend = mock(IActionBackend.class);
        AtomicBoolean appeared = new AtomicBoolean(false);
        IElement target = element(true);
        io.webagent4j.locator.api.IElementReference<IElement> reference =
                () -> {
                    if (!appeared.get()) {
                        throw new LocatorNotFoundException("missing");
                    }
                    return target;
                };

        ActionPlan<Void> plan = new DefaultActionBuilder(context(backend)).click(reference).plan();
        assertThat(plan.status()).isEqualTo(ActionPlanStatus.BLOCKED);
        assertThat(plan.failure().orElseThrow().type())
                .isEqualTo(ActionFailureType.TARGET_NOT_FOUND);

        appeared.set(true);
        ActionResult<Void> result = plan.execute();

        assertThat(result.success()).isTrue();
        verify(backend, times(1)).click(target);
    }

    @Test
    void neverLeaksASecretThroughPlanDiagnosticsOrToString() {
        String marker = "WEBAGENT4J_CONSOLIDATION_SECRET";
        IActionBackend backend = mock(IActionBackend.class);
        IElement target = mock(IElement.class);
        org.mockito.Mockito.when(target.role()).thenReturn(ElementRole.TEXTBOX);
        org.mockito.Mockito.when(target.accessibleName()).thenReturn("Password");
        org.mockito.Mockito.when(target.state())
                .thenReturn(
                        new ElementState(
                                true, true, true, true, false, false, false, false, true, false,
                                false, false));

        ActionPlan<Void> plan =
                new DefaultActionBuilder(context(backend))
                        .typeSecret(target, Secret.of(marker))
                        .plan();

        assertThat(plan.status()).isEqualTo(ActionPlanStatus.READY);
        assertThat(plan.targetDescription()).doesNotContain(marker);
        assertThat(plan.diagnostics().toString()).doesNotContain(marker);
        assertThat(plan.toString()).doesNotContain(marker);
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
        org.mockito.Mockito.when(element.accessibleName()).thenReturn("Confirm");
        org.mockito.Mockito.when(element.state())
                .thenReturn(
                        new ElementState(
                                true, true, enabled, false, false, false, false, false, true,
                                enabled, false, true));
        return element;
    }
}
