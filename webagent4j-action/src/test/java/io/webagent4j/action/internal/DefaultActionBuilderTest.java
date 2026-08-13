package io.webagent4j.action.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.webagent4j.action.ActionFailureType;
import io.webagent4j.action.ActionResult;
import io.webagent4j.action.ActionStage;
import io.webagent4j.action.IActionContext;
import io.webagent4j.dom.ElementState;
import io.webagent4j.dom.IElement;
import io.webagent4j.locator.api.ElementRole;
import io.webagent4j.locator.api.IElementReference;
import org.junit.jupiter.api.Test;

class DefaultActionBuilderTest {

    @Test
    void clicksAndVerifiesTheResultingUrl() {
        IElement element = mock(IElement.class);
        when(element.role()).thenReturn(ElementRole.LINK);
        when(element.accessibleName()).thenReturn("Continue");
        when(element.state()).thenReturn(actionableState());
        IActionContext context = context("https://example.test/done");

        ActionResult<Void> result =
                new DefaultActionBuilder(context)
                        .click(element)
                        .expectUrlContains("/done")
                        .execute();

        assertThat(result.success()).isTrue();
        assertThat(result.events())
                .extracting("stage")
                .contains(ActionStage.BACKEND_ACTION_STARTED, ActionStage.ACTION_COMPLETED);
        verify(element).click();
    }

    @Test
    void returnsAStructuredPostconditionFailure() {
        IElement element = mock(IElement.class);
        when(element.role()).thenReturn(ElementRole.BUTTON);
        when(element.accessibleName()).thenReturn("Submit");
        when(element.state()).thenReturn(actionableState());

        ActionResult<Void> result =
                new DefaultActionBuilder(context("https://example.test/form"))
                        .click(element)
                        .expectUrlContains("/done")
                        .execute();

        assertThat(result.success()).isFalse();
        assertThat(result.failure()).get().extracting("type").isEqualTo(ActionFailureType.TIMEOUT);
    }

    @Test
    void resolvesAReusableReferenceImmediatelyBeforeExecution() {
        IElement initial = mock(IElement.class);
        IElement replacement = mock(IElement.class);
        when(replacement.role()).thenReturn(ElementRole.BUTTON);
        when(replacement.accessibleName()).thenReturn("Confirm");
        when(replacement.state()).thenReturn(actionableState());
        IElementReference<IElement> reference = () -> replacement;

        ActionResult<Void> result =
                new DefaultActionBuilder(context("https://example.test/done"))
                        .click(reference)
                        .execute();

        assertThat(result.success()).isTrue();
        verify(replacement).click();
        org.mockito.Mockito.verifyNoInteractions(initial);
    }

    private static IActionContext context(String url) {
        return new IActionContext() {
            @Override
            public String url() {
                return url;
            }

            @Override
            public String title() {
                return "Example";
            }
        };
    }

    private static ElementState actionableState() {
        return new ElementState(
                true, true, true, false, false, false, false, false, true, true, false, true);
    }
}
