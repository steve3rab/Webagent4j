package io.webagent4j.action.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.webagent4j.action.ActionExecutionMode;
import io.webagent4j.action.ActionFailureType;
import io.webagent4j.action.ActionResult;
import io.webagent4j.action.ActionStatus;
import io.webagent4j.action.IActionBackend;
import io.webagent4j.action.IActionContext;
import io.webagent4j.dom.ElementState;
import io.webagent4j.dom.IElement;
import io.webagent4j.locator.LocatorNotFoundException;
import io.webagent4j.locator.api.ElementRole;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * Documents the full ActionResult execution-semantics contract: for every reachable outcome,
 * status, failureType, executionMode, executed(), and dryRun() must combine in one unambiguous,
 * explicit way. See docs/actions.md for the narrative version of this contract.
 */
class ActionResultMatrixTest {

    @Test
    void resolutionFailure() {
        IActionBackend backend = mock(IActionBackend.class);

        ActionResult<Void> result =
                new DefaultActionBuilder(context(backend))
                        .click(
                                () -> {
                                    throw new LocatorNotFoundException("missing");
                                })
                        .execute();

        assertThat(result.status()).isEqualTo(ActionStatus.EXECUTION_FAILED);
        assertThat(result.failure().orElseThrow().type())
                .isEqualTo(ActionFailureType.TARGET_NOT_FOUND);
        assertThat(result.executionMode()).isEqualTo(ActionExecutionMode.NOT_EXECUTED);
        assertThat(result.executed()).isFalse();
        assertThat(result.dryRun()).isFalse();
    }

    @Test
    void preconditionFailure() {
        IActionBackend backend = mock(IActionBackend.class);
        IElement disabled = element(false);

        ActionResult<Void> result =
                new DefaultActionBuilder(context(backend)).click(disabled).execute();

        assertThat(result.status()).isEqualTo(ActionStatus.PRECONDITION_FAILED);
        assertThat(result.failure().orElseThrow().type())
                .isEqualTo(ActionFailureType.PRECONDITION_FAILED);
        assertThat(result.executionMode()).isEqualTo(ActionExecutionMode.NOT_EXECUTED);
        assertThat(result.executed()).isFalse();
        assertThat(result.dryRun()).isFalse();
        verifyNoInteractions(backend);
    }

    @Test
    void dryRunSuccess() {
        IActionBackend backend = mock(IActionBackend.class);
        IElement target = element(true);

        ActionResult<Void> result =
                new DefaultActionBuilder(context(backend)).click(target).dryRun().execute();

        assertThat(result.status()).isEqualTo(ActionStatus.SUCCESS);
        assertThat(result.failure()).isEmpty();
        assertThat(result.executionMode()).isEqualTo(ActionExecutionMode.DRY_RUN);
        assertThat(result.executed()).isFalse();
        assertThat(result.dryRun()).isTrue();
        verifyNoInteractions(backend);
    }

    @Test
    void realExecutionSuccess() {
        IActionBackend backend = mock(IActionBackend.class);
        IElement target = element(true);

        ActionResult<Void> result =
                new DefaultActionBuilder(context("https://example.test/done", backend))
                        .click(target)
                        .expectUrlContains("/done")
                        .execute();

        assertThat(result.status()).isEqualTo(ActionStatus.SUCCESS);
        assertThat(result.failure()).isEmpty();
        assertThat(result.executionMode()).isEqualTo(ActionExecutionMode.REAL);
        assertThat(result.executed()).isTrue();
        assertThat(result.dryRun()).isFalse();
    }

    @Test
    void backendThrowsDuringExecution() {
        IActionBackend backend = mock(IActionBackend.class);
        IElement target = element(true);
        doThrow(new IllegalStateException("backend disconnected")).when(backend).click(target);

        ActionResult<Void> result =
                new DefaultActionBuilder(context(backend)).click(target).execute();

        assertThat(result.status()).isEqualTo(ActionStatus.EXECUTION_FAILED);
        assertThat(result.failure().orElseThrow().type())
                .isEqualTo(ActionFailureType.BACKEND_FAILURE);
        // The backend call was genuinely made, and may already have produced a side effect, so this
        // is REAL - attempted-but-uncertain - never NOT_EXECUTED, which would wrongly invite a
        // caller to retry.
        assertThat(result.executionMode()).isEqualTo(ActionExecutionMode.REAL);
        assertThat(result.executed()).isTrue();
        assertThat(result.dryRun()).isFalse();
    }

    @Test
    void verificationFailureAfterBackendExecutionSurfacesAsTimeout() {
        IActionBackend backend = mock(IActionBackend.class);
        IElement target = element(true);

        ActionResult<Void> result =
                new DefaultActionBuilder(context("https://example.test/form", backend))
                        .click(target)
                        .expectUrlContains("/done")
                        .timeout(Duration.ofMillis(60))
                        .execute();

        assertThat(result.status()).isEqualTo(ActionStatus.TIMEOUT);
        assertThat(result.failure().orElseThrow().type()).isEqualTo(ActionFailureType.TIMEOUT);
        // The verification poller cannot distinguish "will never succeed" from "not yet true", so
        // every postcondition mismatch it reports has exhausted the action's timeout budget.
        assertThat(result.executionMode()).isEqualTo(ActionExecutionMode.REAL);
        assertThat(result.executed()).isTrue();
        assertThat(result.dryRun()).isFalse();
    }

    private static IActionContext context(IActionBackend backend) {
        return context("https://example.test", backend);
    }

    private static IActionContext context(String url, IActionBackend backend) {
        return new IActionContext() {
            @Override
            public String url() {
                return url;
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
