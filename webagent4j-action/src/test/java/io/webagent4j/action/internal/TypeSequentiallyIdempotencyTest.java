package io.webagent4j.action.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.webagent4j.action.ActionIdempotency;
import io.webagent4j.action.ActionResult;
import io.webagent4j.action.IActionBackend;
import io.webagent4j.action.IActionContext;
import io.webagent4j.action.Secret;
import io.webagent4j.dom.ElementState;
import io.webagent4j.dom.IElement;
import io.webagent4j.locator.api.ElementRole;
import org.junit.jupiter.api.Test;

/**
 * Governed Actions V2 P1 fix: {@code typeSequentially}/{@code typeSequentiallySecret} dispatch real
 * per-character key/input events (Playwright {@code ElementHandle#type}/{@code
 * Locator#pressSequentially}), so replaying the same command can append to or otherwise further
 * modify the target rather than reproducing the same end state - unlike {@code type}/{@code fill},
 * which replace the value directly. These tests prove {@link
 * io.webagent4j.action.ActionType#TYPE_SEQUENCE}'s declared {@link ActionIdempotency} is {@link
 * ActionIdempotency#NON_IDEMPOTENT} for every public construction path, through the actual
 * public/prepared-action metadata API ({@link io.webagent4j.action.IActionPlan#idempotency()}) -
 * never by inspecting {@link ActionCommand} internals directly.
 */
class TypeSequentiallyIdempotencyTest {

    @Test
    void typeSequentiallyOnAFixedElementIsNonIdempotent() {
        ActionIdempotency idempotency =
                new DefaultActionBuilder(context())
                        .typeSequentially(editableElement(), "abc")
                        .plan()
                        .idempotency();

        assertThat(idempotency).isEqualTo(ActionIdempotency.NON_IDEMPOTENT);
    }

    @Test
    void typeSequentiallyOnADynamicallyResolvedReferenceIsNonIdempotent() {
        ActionIdempotency idempotency =
                new DefaultActionBuilder(context())
                        .typeSequentially(() -> editableElement(), "abc")
                        .plan()
                        .idempotency();

        assertThat(idempotency).isEqualTo(ActionIdempotency.NON_IDEMPOTENT);
    }

    @Test
    void typeSequentiallySecretIsNonIdempotent() {
        ActionIdempotency idempotency =
                new DefaultActionBuilder(context())
                        .typeSequentiallySecret(editableElement(), Secret.of("s3cr3t"))
                        .plan()
                        .idempotency();

        assertThat(idempotency).isEqualTo(ActionIdempotency.NON_IDEMPOTENT);
    }

    @Test
    void typeSequentiallyStillInvokesTheBackendExactlyOnceOnSuccess() {
        // The idempotency-classification fix must not regress exactly-once execution: the
        // framework itself never replays a TYPE_SEQUENCE side effect just because it is now
        // correctly marked NON_IDEMPOTENT (that classification only affects how a caller/future
        // retry policy may treat it, never the pipeline's own single-invocation guarantee).
        IActionBackend backend = mock(IActionBackend.class);

        ActionResult<Void> result =
                new DefaultActionBuilder(context(backend))
                        .typeSequentially(editableElement(), "abc")
                        .execute();

        assertThat(result.success()).isTrue();
        verify(backend, times(1)).typeSequentially(any(), anyString());
    }

    @Test
    void typeSequentiallySecretStillInvokesTheBackendExactlyOnceOnSuccessAndNeverLeaksTheSecret() {
        IActionBackend backend = mock(IActionBackend.class);
        String sensitive = "TYPE_SEQUENCE_TEST_SECRET";

        ActionResult<Void> result =
                new DefaultActionBuilder(context(backend))
                        .typeSequentiallySecret(editableElement(), Secret.of(sensitive))
                        .execute();

        assertThat(result.success()).isTrue();
        verify(backend, times(1)).typeSequentiallySecret(any(), any(Secret.class));
        String artifacts = result + result.events().toString() + result.diagnostics().toString();
        assertThat(artifacts).doesNotContain(sensitive);
    }

    private static IElement editableElement() {
        IElement element = mock(IElement.class);
        when(element.role()).thenReturn(ElementRole.TEXTBOX);
        when(element.accessibleName()).thenReturn("Target");
        when(element.state())
                .thenReturn(
                        new ElementState(
                                true, true, true, true, false, false, false, false, true, true,
                                false, true));
        return element;
    }

    private static IActionContext context() {
        return new IActionContext() {
            @Override
            public String url() {
                return "https://example.test";
            }

            @Override
            public String title() {
                return "Example";
            }
        };
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
