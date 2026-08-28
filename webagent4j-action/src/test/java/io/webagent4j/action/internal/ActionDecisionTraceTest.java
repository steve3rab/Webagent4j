package io.webagent4j.action.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.webagent4j.action.ActionDecisionEntry;
import io.webagent4j.action.ActionDecisionKind;
import io.webagent4j.action.ActionDecisionOutcome;
import io.webagent4j.action.ActionDecisionPhase;
import io.webagent4j.action.ActionDecisionTrace;
import io.webagent4j.action.ActionExecutionMode;
import io.webagent4j.action.ActionResult;
import io.webagent4j.action.IActionBackend;
import io.webagent4j.action.IActionContext;
import io.webagent4j.action.IActionPlan;
import io.webagent4j.dom.ElementState;
import io.webagent4j.dom.IElement;
import io.webagent4j.locator.api.ElementRole;
import io.webagent4j.policy.PolicyDecision;
import io.webagent4j.policy.network.INetworkPolicy;
import io.webagent4j.policy.network.NetworkCheckPhase;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Proves {@link ActionResult#decisionTrace()} and {@link IActionPlan#policyDecisions()}: an
 * ungoverned action's trace is empty (including every value produced by a compatibility
 * constructor), a governed action's trace reflects each gate evaluated in the exact order it was
 * evaluated, and a plan's snapshot never gates {@code execute()}'s own fresh re-evaluation.
 */
class ActionDecisionTraceTest {

    @Test
    void ungovernedActionHasAnEmptyDecisionTrace() {
        IActionBackend backend = mock(IActionBackend.class);
        ActionResult<Void> result =
                new DefaultActionBuilder(context(backend)).click(actionableElement()).execute();

        assertThat(result.decisionTrace()).isEqualTo(ActionDecisionTrace.empty());
        assertThat(result.decisionTrace().isEmpty()).isTrue();
    }

    @Test
    void compatibilityConstructedResultHasAnEmptyDecisionTraceRatherThanCrashing() {
        ActionResult<Void> legacy =
                new ActionResult<>(true, null, Duration.ZERO, List.of(), Optional.empty());

        assertThat(legacy.decisionTrace().isEmpty()).isTrue();
    }

    @Test
    void deniedActionPolicyProducesASingleDenyEntry() {
        IActionBackend backend = mock(IActionBackend.class);
        ActionResult<Void> result =
                new DefaultActionBuilder(context(backend))
                        .click(actionableElement())
                        .policy(ctx -> PolicyDecision.deny("test.trace.denied"))
                        .execute();

        List<ActionDecisionEntry> entries = result.decisionTrace().entries();
        assertThat(entries).hasSize(1);
        ActionDecisionEntry entry = entries.get(0);
        assertThat(entry.kind()).isEqualTo(ActionDecisionKind.ACTION);
        assertThat(entry.phase()).isEqualTo(ActionDecisionPhase.PRE_EXECUTION);
        assertThat(entry.outcome()).isEqualTo(ActionDecisionOutcome.DENY);
        assertThat(entry.reason().code()).isEqualTo("test.trace.denied");
    }

    @Test
    void allowedActionPolicyProducesASingleAllowEntry() {
        IActionBackend backend = mock(IActionBackend.class);
        ActionResult<Void> result =
                new DefaultActionBuilder(context(backend))
                        .click(actionableElement())
                        .policy(ctx -> PolicyDecision.allow("test.trace.allowed"))
                        .execute();

        List<ActionDecisionEntry> entries = result.decisionTrace().entries();
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).outcome()).isEqualTo(ActionDecisionOutcome.ALLOW);
    }

    @Test
    void policyEvaluationFailureProducesAnEvaluationFailedEntryNeverLeakingTheRawException() {
        IActionBackend backend = mock(IActionBackend.class);
        String secretMarker = "WEBAGENT4J_TRACE_SECRET_EXCEPTION_TEXT";
        ActionResult<Void> result =
                new DefaultActionBuilder(context(backend))
                        .click(actionableElement())
                        .policy(
                                ctx -> {
                                    throw new RuntimeException(secretMarker);
                                })
                        .execute();

        List<ActionDecisionEntry> entries = result.decisionTrace().entries();
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).outcome()).isEqualTo(ActionDecisionOutcome.EVALUATION_FAILED);
        assertThat(entries.get(0).reason().code()).doesNotContain(secretMarker);
        assertThat(result.toCompactText()).doesNotContain(secretMarker);
    }

    @Test
    void navigateActionRecordsActionThenNetworkDecisionsInEvaluationOrder() {
        IActionBackend backend = mock(IActionBackend.class);
        MutableUrlContext context = new MutableUrlContext("https://start.example.test", backend);
        org.mockito.Mockito.doAnswer(
                        invocation -> {
                            context.setUrl(invocation.getArgument(0));
                            return null;
                        })
                .when(backend)
                .navigate(org.mockito.ArgumentMatchers.anyString());
        INetworkPolicy allowAll = ctx -> PolicyDecision.allow("test.network.allowed");

        ActionResult<Void> result =
                new DefaultActionBuilder(context)
                        .navigate("https://allowed.example.test/")
                        .policy(ctx -> PolicyDecision.allow("test.action.allowed"))
                        .networkPolicy(allowAll)
                        .execute();

        // ACTION-PRE, then NETWORK-PRE (before the backend call), then NETWORK-POST (checking the
        // final URL after navigation genuinely happened) - a NAVIGATE action with both policies
        // configured always produces all three, in exactly this evaluation order.
        List<ActionDecisionEntry> entries = result.decisionTrace().entries();
        assertThat(entries).hasSize(3);
        assertThat(entries.get(0).kind()).isEqualTo(ActionDecisionKind.ACTION);
        assertThat(entries.get(0).phase()).isEqualTo(ActionDecisionPhase.PRE_EXECUTION);
        assertThat(entries.get(1).kind()).isEqualTo(ActionDecisionKind.NETWORK);
        assertThat(entries.get(1).phase()).isEqualTo(ActionDecisionPhase.PRE_EXECUTION);
        assertThat(entries.get(2).kind()).isEqualTo(ActionDecisionKind.NETWORK);
        assertThat(entries.get(2).phase()).isEqualTo(ActionDecisionPhase.POST_EXECUTION);
        assertThat(entries)
                .allSatisfy(
                        entry ->
                                assertThat(entry.outcome()).isEqualTo(ActionDecisionOutcome.ALLOW));
    }

    @Test
    void postNavigationNetworkViolationIsRecordedAtPostExecutionPhase() {
        IActionBackend backend = mock(IActionBackend.class);
        MutableUrlContext context = new MutableUrlContext("https://start.example.test", backend);
        org.mockito.Mockito.doAnswer(
                        invocation -> {
                            context.setUrl("https://redirected-to-denied.example.test/");
                            return null;
                        })
                .when(backend)
                .navigate("https://allowed.example.test/");
        INetworkPolicy allowFirstDenySecond =
                ctx ->
                        ctx.phase() == NetworkCheckPhase.POST_REQUEST
                                ? PolicyDecision.deny("test.network.post.denied")
                                : PolicyDecision.allow("test.network.pre.allowed");

        ActionResult<Void> result =
                new DefaultActionBuilder(context)
                        .navigate("https://allowed.example.test/")
                        .networkPolicy(allowFirstDenySecond)
                        .execute();

        List<ActionDecisionEntry> entries = result.decisionTrace().entries();
        assertThat(entries).hasSize(2);
        assertThat(entries.get(0).phase()).isEqualTo(ActionDecisionPhase.PRE_EXECUTION);
        assertThat(entries.get(0).outcome()).isEqualTo(ActionDecisionOutcome.ALLOW);
        assertThat(entries.get(1).phase()).isEqualTo(ActionDecisionPhase.POST_EXECUTION);
        assertThat(entries.get(1).outcome()).isEqualTo(ActionDecisionOutcome.DENY);
    }

    @Test
    void deniedPlanPolicySnapshotBlocksThePlanButStillNeverGatesExecute() {
        IActionBackend backend = mock(IActionBackend.class);
        IActionPlan<Void> plan =
                new DefaultActionBuilder(context(backend))
                        .click(actionableElement())
                        .policy(ctx -> PolicyDecision.deny("test.plan.denied"))
                        .plan();

        assertThat(plan.policyDecisions()).hasSize(1);
        assertThat(plan.policyDecisions().get(0).outcome()).isEqualTo(ActionDecisionOutcome.DENY);

        // Denied in the snapshot: the plan itself is BLOCKED, never a false READY promise.
        assertThat(plan.status()).isEqualTo(io.webagent4j.action.ActionPlanStatus.BLOCKED);
        assertThat(plan.failure().orElseThrow().type())
                .isEqualTo(io.webagent4j.action.ActionFailureType.POLICY_DENIED);

        // The snapshot still never gates execute() itself: the same, still-denying policy is
        // re-evaluated fresh, and execute() reports that live decision independently.
        ActionResult<Void> result = plan.execute();
        assertThat(result.success()).isFalse();
        assertThat(result.failure().orElseThrow().type())
                .isEqualTo(io.webagent4j.action.ActionFailureType.POLICY_DENIED);
        assertThat(result.executionMode()).isEqualTo(ActionExecutionMode.NOT_EXECUTED);
    }

    @Test
    void allowedPlanPolicySnapshotKeepsThePlanReady() {
        IActionBackend backend = mock(IActionBackend.class);
        IActionPlan<Void> plan =
                new DefaultActionBuilder(context(backend))
                        .click(actionableElement())
                        .policy(ctx -> PolicyDecision.allow("test.plan.allowed"))
                        .plan();

        assertThat(plan.policyDecisions()).hasSize(1);
        assertThat(plan.policyDecisions().get(0).outcome()).isEqualTo(ActionDecisionOutcome.ALLOW);
        assertThat(plan.status()).isEqualTo(io.webagent4j.action.ActionPlanStatus.READY);
        assertThat(plan.failure()).isEmpty();
    }

    @Test
    void planPolicyEvaluationFailureBlocksThePlan() {
        IActionBackend backend = mock(IActionBackend.class);
        IActionPlan<Void> plan =
                new DefaultActionBuilder(context(backend))
                        .click(actionableElement())
                        .policy(
                                ctx -> {
                                    throw new RuntimeException("plan-time policy boom");
                                })
                        .plan();

        assertThat(plan.policyDecisions()).hasSize(1);
        assertThat(plan.policyDecisions().get(0).outcome())
                .isEqualTo(ActionDecisionOutcome.EVALUATION_FAILED);
        assertThat(plan.status()).isEqualTo(io.webagent4j.action.ActionPlanStatus.BLOCKED);
        assertThat(plan.failure().orElseThrow().type())
                .isEqualTo(io.webagent4j.action.ActionFailureType.POLICY_EVALUATION_FAILED);
    }

    @Test
    void ungovernedPlanHasAnEmptyPolicyDecisionsSnapshot() {
        IActionBackend backend = mock(IActionBackend.class);
        IActionPlan<Void> plan =
                new DefaultActionBuilder(context(backend)).click(actionableElement()).plan();

        assertThat(plan.policyDecisions()).isEmpty();
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
        when(element.role()).thenReturn(ElementRole.BUTTON);
        when(element.accessibleName()).thenReturn("Target");
        when(element.state())
                .thenReturn(
                        new ElementState(
                                true, true, true, false, false, false, false, false, true, true,
                                false, true));
        // A mock never inherits IElement's real default implementation, so it must be told
        // explicitly that it is still the same target - matching what a backend without physical
        // -node identity tracking would report through that default method.
        when(element.isStillTheOriginallyResolvedTarget()).thenReturn(true);
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
