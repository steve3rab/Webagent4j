package io.webagent4j.action.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import io.webagent4j.action.ActionExecutionMode;
import io.webagent4j.action.ActionFailureType;
import io.webagent4j.action.ActionResult;
import io.webagent4j.action.ActionStatus;
import io.webagent4j.action.IActionBackend;
import io.webagent4j.action.IActionContext;
import io.webagent4j.dom.ElementState;
import io.webagent4j.dom.IElement;
import io.webagent4j.locator.api.ElementRole;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * Proves that a global action budget consumed entirely by target resolution blocks the backend side
 * effect outright: {@code WebAgent4J} never starts a backend action after its budget has already
 * expired, and never retries the side effect as part of wait/poll logic.
 */
class ActionBudgetExpiredBeforeBackendTest {

    @Test
    void aBudgetAlreadyConsumedByResolutionPreventsTheBackendSideEffectEntirely() throws Exception {
        IActionBackend backend = mock(IActionBackend.class);
        IActionContext context =
                new IActionContext() {
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
        IElement target = enabledElement();
        // Resolution alone takes 10x the action's configured timeout, so by the time it returns
        // the shared budget is already well past its deadline.
        ActionResult<Void> result =
                new DefaultActionBuilder(context)
                        .click(
                                () -> {
                                    try {
                                        Thread.sleep(50);
                                    } catch (InterruptedException interrupted) {
                                        Thread.currentThread().interrupt();
                                    }
                                    return target;
                                })
                        .timeout(Duration.ofMillis(5))
                        .execute();

        assertThat(result.status()).isEqualTo(ActionStatus.TIMEOUT);
        assertThat(result.failure().orElseThrow().type()).isEqualTo(ActionFailureType.TIMEOUT);
        assertThat(result.executionMode()).isEqualTo(ActionExecutionMode.NOT_EXECUTED);
        assertThat(result.executed()).isFalse();
        verifyNoInteractions(backend);
    }

    private static IElement enabledElement() {
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
