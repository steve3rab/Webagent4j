package io.webagent4j.action.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.webagent4j.action.ActionExecutionMode;
import io.webagent4j.action.ActionResult;
import io.webagent4j.action.IActionBackend;
import io.webagent4j.action.IActionContext;
import io.webagent4j.action.IActionPlan;
import io.webagent4j.action.IPreparedAction;
import io.webagent4j.dom.ElementState;
import io.webagent4j.dom.IElement;
import io.webagent4j.locator.api.ElementRole;
import org.junit.jupiter.api.Test;

/**
 * Proves {@code dryRun()} and {@code plan()} are mutually exclusive terminal modes on the same
 * prepared action, and that a plan built without {@code dryRun()} always performs the real action
 * when executed.
 */
class DryRunPlanContractTest {

    @Test
    void planAfterDryRunFailsExplicitlyRatherThanSilentlyBecomingADryRunPlan() {
        IActionBackend backend = mock(IActionBackend.class);
        IElement target = element();
        IPreparedAction<Void> prepared =
                new DefaultActionBuilder(context(backend)).click(target).dryRun();

        assertThatIllegalStateException().isThrownBy(prepared::plan);
    }

    @Test
    void aPlanBuiltWithoutDryRunAlwaysPerformsTheRealActionWhenExecuted() {
        IActionBackend backend = mock(IActionBackend.class);
        IElement target = element();

        IActionPlan<Void> plan = new DefaultActionBuilder(context(backend)).click(target).plan();
        ActionResult<Void> result = plan.execute();

        assertThat(result.executionMode()).isEqualTo(ActionExecutionMode.REAL);
        assertThat(result.executed()).isTrue();
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
