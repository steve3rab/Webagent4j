package io.webagent4j.action.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import io.webagent4j.action.ActionExecutionMode;
import io.webagent4j.action.ActionFailureType;
import io.webagent4j.action.ActionResult;
import io.webagent4j.action.IActionBackend;
import io.webagent4j.action.IActionContext;
import io.webagent4j.action.Secret;
import io.webagent4j.dom.ElementState;
import io.webagent4j.dom.IElement;
import io.webagent4j.locator.api.ElementRole;
import io.webagent4j.policy.PolicyDecision;
import io.webagent4j.policy.network.INetworkPolicy;
import io.webagent4j.policy.network.NetworkCheckPhase;
import org.junit.jupiter.api.Test;

/**
 * Closing adversarial coverage for governed execution's two highest-value invariants, spanning
 * commits 1-5: (1) once a side effect may have happened, it is never reported as {@code
 * NOT_EXECUTED}, even when the failure that follows is the policy layer's own fault; (2) no
 * governed-execution type ever becomes a channel for leaking secret text, even when a policy itself
 * is the thing that denies a sensitive action.
 */
class ActionAuthorizationAdversarialTest {

    @Test
    void backendThrowingAfterAllowIsNeverBlamedOnPolicyAndStaysReal() {
        IActionBackend backend = mock(IActionBackend.class);
        IElement target = actionableElement();
        RuntimeException backendFailure = new RuntimeException("backend crashed");
        doThrow(backendFailure).when(backend).click(target);

        ActionResult<Void> result =
                new DefaultActionBuilder(context(backend))
                        .click(target)
                        .policy(ctx -> PolicyDecision.allow("test.allowed"))
                        .execute();

        assertThat(result.success()).isFalse();
        assertThat(result.executionMode()).isEqualTo(ActionExecutionMode.REAL);
        assertThat(result.executed()).isTrue();
        assertThat(result.failure().orElseThrow().type())
                .isEqualTo(ActionFailureType.BACKEND_FAILURE);
        verify(backend, times(1)).click(target);
    }

    @Test
    void postNavigationNetworkPolicyEvaluationFailureStaysRealNeverNotExecuted() {
        IActionBackend backend = mock(IActionBackend.class);
        MutableUrlContext context = new MutableUrlContext("https://start.example.test", backend);
        org.mockito.Mockito.doAnswer(
                        invocation -> {
                            context.setUrl(invocation.getArgument(0));
                            return null;
                        })
                .when(backend)
                .navigate(org.mockito.ArgumentMatchers.anyString());
        INetworkPolicy allowPreThrowPost =
                ctx -> {
                    if (ctx.phase() == NetworkCheckPhase.POST_REQUEST) {
                        throw new RuntimeException("network policy backend unavailable");
                    }
                    return PolicyDecision.allow("test.network.pre.allowed");
                };

        ActionResult<Void> result =
                new DefaultActionBuilder(context)
                        .navigate("https://allowed.example.test/")
                        .networkPolicy(allowPreThrowPost)
                        .execute();

        assertThat(result.success()).isFalse();
        // The navigation genuinely happened before the post-check ever ran - this must never be
        // reported as NOT_EXECUTED, since a caller could otherwise be misled into unsafely
        // retrying a navigation that already occurred.
        assertThat(result.executionMode()).isEqualTo(ActionExecutionMode.REAL);
        assertThat(result.executed()).isTrue();
        assertThat(result.failure().orElseThrow().type())
                .isEqualTo(ActionFailureType.POLICY_VIOLATION);
        verify(backend, times(1)).navigate("https://allowed.example.test/");
    }

    @Test
    void secretTypedValueNeverLeaksThroughAnyGovernedExecutionRendererEvenWhenDenied() {
        IActionBackend backend = mock(IActionBackend.class);
        IElement target = editableElement();
        String secretMarker = "WEBAGENT4J_GOVERNED_EXECUTION_SECRET_MARKER";

        ActionResult<Void> result =
                new DefaultActionBuilder(context(backend))
                        .typeSecret(target, Secret.of(secretMarker))
                        .policy(ctx -> PolicyDecision.deny("test.secret.denied"))
                        .execute();

        assertThat(result.success()).isFalse();
        assertThat(result.failure().orElseThrow().type())
                .isEqualTo(ActionFailureType.POLICY_DENIED);
        assertThat(result.toCompactText()).doesNotContain(secretMarker);
        assertThat(result.decisionTrace().toString()).doesNotContain(secretMarker);
        assertThat(result.diagnostics().toString()).doesNotContain(secretMarker);
        for (var event : result.events()) {
            assertThat(event.toString()).doesNotContain(secretMarker);
        }
        verify(backend, org.mockito.Mockito.never())
                .fillSecret(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void secretTypedValueNeverLeaksWhenPolicyEvaluationItselfThrowsWithTheSecretInItsMessage() {
        IActionBackend backend = mock(IActionBackend.class);
        IElement target = editableElement();
        String secretMarker = "WEBAGENT4J_GOVERNED_EXECUTION_EXCEPTION_SECRET";

        ActionResult<Void> result =
                new DefaultActionBuilder(context(backend))
                        .typeSecret(target, Secret.of("irrelevant-value"))
                        .policy(
                                ctx -> {
                                    // A misbehaving policy that happened to see something
                                    // sensitive elsewhere in its own process and put it in an
                                    // exception message - this framework never lets that reach
                                    // any of its own safe renderers even though the raw cause is
                                    // still accessible in-process via ActionFailure.cause().
                                    throw new RuntimeException(secretMarker);
                                })
                        .execute();

        assertThat(result.toCompactText()).doesNotContain(secretMarker);
        assertThat(result.decisionTrace().toString()).doesNotContain(secretMarker);
        for (var event : result.events()) {
            assertThat(event.toString()).doesNotContain(secretMarker);
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

    private static IElement actionableElement() {
        IElement element = mock(IElement.class);
        org.mockito.Mockito.when(element.role()).thenReturn(ElementRole.BUTTON);
        org.mockito.Mockito.when(element.accessibleName()).thenReturn("Target");
        org.mockito.Mockito.when(element.state())
                .thenReturn(
                        new ElementState(
                                true, true, true, false, false, false, false, false, true, true,
                                false, true));
        // A mock never inherits IElement's real default implementation, so it must be told
        // explicitly that it is still the same target - matching what a backend without physical
        // -node identity tracking would report through that default method.
        org.mockito.Mockito.when(element.isStillTheOriginallyResolvedTarget()).thenReturn(true);
        return element;
    }

    private static IElement editableElement() {
        IElement element = mock(IElement.class);
        org.mockito.Mockito.when(element.role()).thenReturn(ElementRole.TEXTBOX);
        org.mockito.Mockito.when(element.accessibleName()).thenReturn("Password");
        org.mockito.Mockito.when(element.state())
                .thenReturn(
                        new ElementState(
                                true, true, true, true, false, false, false, false, true, true,
                                false, true));
        return element;
    }

    private static final class MutableUrlContext implements IActionContext {
        private String url;
        private final IActionBackend backend;

        MutableUrlContext(String initialUrl, IActionBackend backend) {
            this.url = initialUrl;
            this.backend = backend;
        }

        void setUrl(String url) {
            this.url = url;
        }

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
    }
}
