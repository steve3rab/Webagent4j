package io.webagent4j.action.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.webagent4j.action.IActionBackend;
import io.webagent4j.action.IActionContext;
import io.webagent4j.action.IActionPlan;
import io.webagent4j.dom.ElementState;
import io.webagent4j.dom.IElement;
import io.webagent4j.locator.api.ElementRole;
import org.junit.jupiter.api.Test;

/**
 * Proves {@link IActionPlan#execute()} runs the backend at most once per plan instance. A plan can
 * represent a non-idempotent operation (submit, delete, pay, confirm), so a caller accidentally
 * calling {@code execute()} twice must never be able to produce two side effects.
 */
class ActionPlanDoubleExecutionTest {

    @Test
    void aSecondExecuteCallIsRejectedAndNeverTouchesTheBackendAgain() {
        IActionBackend backend = mock(IActionBackend.class);
        IElement target = element();
        IActionPlan<Void> plan = new DefaultActionBuilder(context(backend)).click(target).plan();

        assertThat(plan.execute().success()).isTrue();

        assertThatIllegalStateException().isThrownBy(plan::execute);
        verify(backend, times(1)).click(target);
    }

    @Test
    void aFailingFirstExecutionStillCountsAsTheOneAttempt() {
        IActionBackend backend = mock(IActionBackend.class);
        IElement target = element();
        doThrow(new IllegalStateException("backend disconnected")).when(backend).click(target);
        IActionPlan<Void> plan = new DefaultActionBuilder(context(backend)).click(target).plan();

        assertThat(plan.execute().success()).isFalse();

        assertThatIllegalStateException().isThrownBy(plan::execute);
        verify(backend, times(1)).click(target);
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
        when(element.role()).thenReturn(ElementRole.BUTTON);
        when(element.accessibleName()).thenReturn("Confirm");
        when(element.state())
                .thenReturn(
                        new ElementState(
                                true, true, true, false, false, false, false, false, true, true,
                                false, true));
        return element;
    }
}
