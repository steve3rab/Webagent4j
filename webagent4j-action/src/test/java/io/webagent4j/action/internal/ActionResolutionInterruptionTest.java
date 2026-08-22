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
import io.webagent4j.common.RetryPolicy;
import io.webagent4j.dom.ElementState;
import io.webagent4j.dom.IElement;
import io.webagent4j.locator.LocatorNotFoundException;
import io.webagent4j.locator.api.ElementRole;
import io.webagent4j.locator.api.IElementReference;
import io.webagent4j.verification.VerificationResult;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ActionResolutionInterruptionTest {

    @Test
    void preservesAnInterruptionBeforeBackendInvocationAsNotExecutedCancellation() {
        IActionBackend backend = mock(IActionBackend.class);
        AtomicInteger resolutionAttempts = new AtomicInteger();
        IElementReference<IElement> target =
                () -> {
                    resolutionAttempts.incrementAndGet();
                    Thread.currentThread().interrupt();
                    throw new LocatorNotFoundException("missing");
                };

        try {
            ActionResult<Void> result =
                    new DefaultActionBuilder(context(backend))
                            .click(target)
                            .retry(
                                    new RetryPolicy(
                                            2, Duration.ofSeconds(1), 1.0, Duration.ofSeconds(1)))
                            .execute();

            assertThat(result.status()).isEqualTo(ActionStatus.CANCELLED);
            assertThat(result.executionMode()).isEqualTo(ActionExecutionMode.NOT_EXECUTED);
            assertThat(result.failure().orElseThrow().type())
                    .isEqualTo(ActionFailureType.INTERRUPTED);
            assertThat(result.executed()).isFalse();
            assertThat(result.dryRun()).isFalse();
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            assertThat(resolutionAttempts).hasValue(1);
            verifyNoInteractions(backend);
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void preservesAnInterruptionAfterBackendInvocationAsRealCancellation() {
        IActionBackend backend = mock(IActionBackend.class);
        IElement target = mock(IElement.class);
        org.mockito.Mockito.when(target.role()).thenReturn(ElementRole.BUTTON);
        org.mockito.Mockito.when(target.accessibleName()).thenReturn("Confirm");
        org.mockito.Mockito.when(target.state())
                .thenReturn(
                        new ElementState(
                                true, true, true, false, false, false, false, false, true, true,
                                false, true));

        try {
            ActionResult<Void> result =
                    new DefaultActionBuilder(context(backend))
                            .click(target)
                            .expect(
                                    ignored -> {
                                        Thread.currentThread().interrupt();
                                        return new VerificationResult(false, "updated", "pending");
                                    })
                            .execute();

            assertThat(result.status()).isEqualTo(ActionStatus.CANCELLED);
            assertThat(result.executionMode()).isEqualTo(ActionExecutionMode.REAL);
            assertThat(result.failure().orElseThrow().type())
                    .isEqualTo(ActionFailureType.INTERRUPTED);
            assertThat(result.executed()).isTrue();
            assertThat(result.dryRun()).isFalse();
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            org.mockito.Mockito.verify(backend).click(target);
        } finally {
            Thread.interrupted();
        }
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
}
