package io.webagent4j.action.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import io.webagent4j.action.ActionExecutionMode;
import io.webagent4j.action.ActionFailureType;
import io.webagent4j.action.ActionResult;
import io.webagent4j.action.IActionBackend;
import io.webagent4j.action.IActionContext;
import io.webagent4j.dom.IElement;
import io.webagent4j.locator.AmbiguousLocatorException;
import io.webagent4j.locator.LocatorNotFoundException;
import io.webagent4j.locator.api.IElementReference;
import org.junit.jupiter.api.Test;

/**
 * Proves that target-resolution failures are classified through the typed {@link
 * io.webagent4j.common.ILocatorFailure} contract rather than exception class names, and that a
 * genuine backend/runtime failure during resolution is never reported as a missing target -
 * directly or wrapped by an unrelated {@code RuntimeException}.
 */
class ActionResolutionClassificationTest {

    @Test
    void classifiesADirectNotFoundFailureAsTargetNotFound() {
        assertBlockedBeforeBackend(
                () -> {
                    throw new LocatorNotFoundException("missing");
                },
                ActionFailureType.TARGET_NOT_FOUND);
    }

    @Test
    void classifiesADirectAmbiguousFailureAsTargetAmbiguous() {
        assertBlockedBeforeBackend(
                () -> {
                    throw new AmbiguousLocatorException("ambiguous");
                },
                ActionFailureType.TARGET_AMBIGUOUS);
    }

    @Test
    void classifiesAGenericBackendFailureDuringResolutionAsBackendFailureNeverNotFound() {
        assertBlockedBeforeBackend(
                () -> {
                    throw new IllegalStateException("browser crashed");
                },
                ActionFailureType.BACKEND_FAILURE);
    }

    @Test
    void classifiesANotFoundFailureWrappedByAnUnrelatedRuntimeExceptionAsTargetNotFound() {
        assertBlockedBeforeBackend(
                () -> {
                    throw new RuntimeException("wrapper", new LocatorNotFoundException("missing"));
                },
                ActionFailureType.TARGET_NOT_FOUND);
    }

    @Test
    void classifiesAWrappedBackendFailureAsBackendFailureNeverNotFound() {
        assertBlockedBeforeBackend(
                () -> {
                    throw new RuntimeException(
                            "wrapper", new IllegalStateException("backend disconnected"));
                },
                ActionFailureType.BACKEND_FAILURE);
    }

    private static void assertBlockedBeforeBackend(
            IElementReference<IElement> failingTarget, ActionFailureType expected) {
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

        ActionResult<Void> result =
                new DefaultActionBuilder(context).click(failingTarget).execute();

        assertThat(result.success()).isFalse();
        assertThat(result.failure().orElseThrow().type()).isEqualTo(expected);
        assertThat(result.executionMode()).isEqualTo(ActionExecutionMode.NOT_EXECUTED);
        assertThat(result.executed()).isFalse();
        verifyNoInteractions(backend);
    }
}
