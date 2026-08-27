package io.webagent4j.action.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import io.webagent4j.action.ActionExecutionMode;
import io.webagent4j.action.ActionFailureType;
import io.webagent4j.action.ActionResult;
import io.webagent4j.action.IActionBackend;
import io.webagent4j.action.IActionContext;
import io.webagent4j.action.IPreparedAction;
import io.webagent4j.dom.ElementState;
import io.webagent4j.dom.IElement;
import io.webagent4j.locator.api.ElementRole;
import io.webagent4j.policy.PolicyDecision;
import io.webagent4j.policy.network.INetworkPolicy;
import io.webagent4j.policy.network.NetworkCheckPhase;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Proves network-destination governance on a governed {@code NAVIGATE} action: a pre-request deny
 * means zero navigation, a post-request violation (only detectable after the browser already
 * navigated) is reported as {@code REAL} + {@code POLICY_VIOLATION} - never {@code NOT_EXECUTED} -
 * and network governance is rejected outright on any action type other than {@code NAVIGATE}.
 */
class ActionNetworkAuthorizationTest {

    @Test
    void preRequestDenyNeverInvokesNavigate() {
        IActionBackend backend = mock(IActionBackend.class);
        MutableUrlContext context = new MutableUrlContext("https://start.example.test", backend);
        INetworkPolicy denyAll = networkContext -> PolicyDecision.deny("test.network.denied");

        ActionResult<Void> result =
                new DefaultActionBuilder(context)
                        .navigate("https://attacker.test/")
                        .networkPolicy(denyAll)
                        .execute();

        assertThat(result.success()).isFalse();
        assertThat(result.executionMode()).isEqualTo(ActionExecutionMode.NOT_EXECUTED);
        assertThat(result.failure().orElseThrow().type())
                .isEqualTo(ActionFailureType.POLICY_DENIED);
        verify(backend, never()).navigate(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void preRequestAllowInvokesNavigateExactlyOnce() {
        IActionBackend backend = mock(IActionBackend.class);
        MutableUrlContext context = new MutableUrlContext("https://start.example.test", backend);
        stubNavigateUpdatesContextUrl(backend, context);
        INetworkPolicy allowAll = networkContext -> PolicyDecision.allow("test.network.allowed");

        ActionResult<Void> result =
                new DefaultActionBuilder(context)
                        .navigate("https://allowed.example.test/")
                        .networkPolicy(allowAll)
                        .execute();

        assertThat(result.success()).isTrue();
        assertThat(result.executed()).isTrue();
        verify(backend, times(1)).navigate("https://allowed.example.test/");
    }

    @Test
    void postRequestViolationAfterRedirectReportsRealExecutionAndPolicyViolation() {
        IActionBackend backend = mock(IActionBackend.class);
        MutableUrlContext context = new MutableUrlContext("https://start.example.test", backend);
        // Simulate a browser-side redirect: navigate() is asked for "allowed.example.test" but the
        // browser's own internal redirect lands the context on a different, disallowed host -
        // exactly the scenario this framework cannot intercept mid-flight.
        doAnswer(
                        invocation -> {
                            context.setUrl("https://redirected-to-denied.example.test/");
                            return null;
                        })
                .when(backend)
                .navigate("https://allowed.example.test/");
        List<NetworkCheckPhase> observedPhases = new ArrayList<>();
        INetworkPolicy allowFirstDenySecond =
                networkContext -> {
                    observedPhases.add(networkContext.phase());
                    boolean isFinalCheck = networkContext.phase() == NetworkCheckPhase.POST_REQUEST;
                    return isFinalCheck
                            ? PolicyDecision.deny("test.network.redirect.denied")
                            : PolicyDecision.allow("test.network.allowed");
                };

        ActionResult<Void> result =
                new DefaultActionBuilder(context)
                        .navigate("https://allowed.example.test/")
                        .networkPolicy(allowFirstDenySecond)
                        .execute();

        assertThat(result.success()).isFalse();
        // The navigation genuinely happened - executed() must stay true, never falsely
        // NOT_EXECUTED, since the browser already performed the side effect.
        assertThat(result.executed()).isTrue();
        assertThat(result.executionMode()).isEqualTo(ActionExecutionMode.REAL);
        assertThat(result.failure().orElseThrow().type())
                .isEqualTo(ActionFailureType.POLICY_VIOLATION);
        assertThat(observedPhases)
                .containsExactly(NetworkCheckPhase.PRE_REQUEST, NetworkCheckPhase.POST_REQUEST);
        verify(backend, times(1)).navigate("https://allowed.example.test/");
    }

    @Test
    void malformedNavigationUrlFailsClosedBeforeBackend() {
        IActionBackend backend = mock(IActionBackend.class);
        MutableUrlContext context = new MutableUrlContext("https://start.example.test", backend);
        INetworkPolicy allowAll = networkContext -> PolicyDecision.allow("test.network.allowed");

        ActionResult<Void> result =
                new DefaultActionBuilder(context)
                        .navigate("not a valid uri {}[]")
                        .networkPolicy(allowAll)
                        .execute();

        assertThat(result.success()).isFalse();
        assertThat(result.executionMode()).isEqualTo(ActionExecutionMode.NOT_EXECUTED);
        assertThat(result.failure().orElseThrow().type())
                .isEqualTo(ActionFailureType.POLICY_EVALUATION_FAILED);
        verify(backend, never()).navigate(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void networkPolicyIsRejectedOnNonNavigateActionTypes() {
        IActionBackend backend = mock(IActionBackend.class);
        IElement target = actionableElement();
        IPreparedAction<Void> prepared =
                new DefaultActionBuilder(
                                new MutableUrlContext("https://start.example.test", backend))
                        .click(target);

        assertThatThrownBy(
                        () -> prepared.networkPolicy(networkContext -> PolicyDecision.allow("x")))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void networkPolicyMethodRejectsNull() {
        IActionBackend backend = mock(IActionBackend.class);
        IPreparedAction<Void> prepared =
                new DefaultActionBuilder(
                                new MutableUrlContext("https://start.example.test", backend))
                        .navigate("https://example.test/");

        assertThatThrownBy(() -> prepared.networkPolicy(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void networkPolicyMethodRejectsDuplicateConfiguration() {
        IActionBackend backend = mock(IActionBackend.class);
        IPreparedAction<Void> prepared =
                new DefaultActionBuilder(
                                new MutableUrlContext("https://start.example.test", backend))
                        .navigate("https://example.test/");
        prepared.networkPolicy(networkContext -> PolicyDecision.allow("first"));

        assertThatThrownBy(
                        () ->
                                prepared.networkPolicy(
                                        networkContext -> PolicyDecision.allow("second")))
                .isInstanceOf(IllegalStateException.class);
    }

    private static void stubNavigateUpdatesContextUrl(
            IActionBackend backend, MutableUrlContext context) {
        doAnswer(
                        invocation -> {
                            context.setUrl(invocation.getArgument(0));
                            return null;
                        })
                .when(backend)
                .navigate(org.mockito.ArgumentMatchers.anyString());
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
        return element;
    }

    /**
     * A minimal {@link IActionContext} whose {@link #url()} can be mutated to simulate navigation.
     */
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
