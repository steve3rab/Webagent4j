package io.webagent4j.action.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.webagent4j.action.ActionPlan;
import io.webagent4j.action.ActionResult;
import io.webagent4j.action.IActionBackend;
import io.webagent4j.action.IActionContext;
import io.webagent4j.dom.ElementState;
import io.webagent4j.dom.IElement;
import io.webagent4j.locator.api.ElementRole;
import org.junit.jupiter.api.Test;

/**
 * Proves {@link ActionPlan#actionId()} and its eventual {@link ActionPlan#execute()} result share
 * the same {@link io.webagent4j.action.ActionId}, even though {@code execute()} reruns the whole
 * pipeline (a fresh resolution, fresh preconditions) rather than reusing anything captured at plan
 * time.
 */
class ActionPlanCorrelationTest {

    @Test
    void theExecutedResultCarriesThePlansActionId() {
        IActionBackend backend = mock(IActionBackend.class);
        IElement target = element();

        ActionPlan<Void> plan = new DefaultActionBuilder(context(backend)).click(target).plan();
        ActionResult<Void> result = plan.execute();

        assertThat(result.actionId()).isEqualTo(plan.actionId());
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
