package io.webagent4j.action.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.webagent4j.action.ActionExecutionMode;
import io.webagent4j.action.ActionFailureType;
import io.webagent4j.action.ActionPlanStatus;
import io.webagent4j.action.ActionResult;
import io.webagent4j.action.ActionStatus;
import io.webagent4j.action.IActionBackend;
import io.webagent4j.action.IActionContext;
import io.webagent4j.action.IActionPlan;
import io.webagent4j.dom.ElementState;
import io.webagent4j.dom.IElement;
import io.webagent4j.locator.api.ElementRole;
import io.webagent4j.policy.PolicyDecision;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Mock-level proof of the TOCTOU revalidation gate ({@link
 * io.webagent4j.dom.IElement#isStillTheOriginallyResolvedTarget()}), independent of the
 * real-browser proof in the integration test suite: an action policy's {@code ALLOW} for one
 * resolved target must never reach the backend once that target's identity can no longer be proven,
 * whether the action is executed directly or through a plan, and a plan's single-use guard remains
 * at-most-once under real concurrent execution.
 */
class ActionTargetIdentityRevalidationTest {

    @Test
    void allowedPolicyNeverInvokesTheBackendWhenTargetIdentityCannotBeProven() {
        IActionBackend backend = mock(IActionBackend.class);
        IElement target = element(false);

        ActionResult<Void> result =
                new DefaultActionBuilder(context(backend))
                        .click(target)
                        .policy(ctx -> PolicyDecision.allow("test.identity.allowed"))
                        .execute();

        assertThat(result.success()).isFalse();
        assertThat(result.status()).isEqualTo(ActionStatus.EXECUTION_FAILED);
        assertThat(result.executionMode()).isEqualTo(ActionExecutionMode.NOT_EXECUTED);
        assertThat(result.executed()).isFalse();
        assertThat(result.failure().orElseThrow().type())
                .isEqualTo(ActionFailureType.TARGET_CHANGED);
        verify(backend, never()).click(target);
    }

    @Test
    void allowedPolicyStillInvokesTheBackendExactlyOnceWhenTargetIdentityIsProven() {
        IActionBackend backend = mock(IActionBackend.class);
        IElement target = element(true);

        ActionResult<Void> result =
                new DefaultActionBuilder(context(backend))
                        .click(target)
                        .policy(ctx -> PolicyDecision.allow("test.identity.allowed"))
                        .execute();

        assertThat(result.success()).isTrue();
        verify(backend, times(1)).click(target);
    }

    @Test
    void ungovernedActionNeverConsultsTargetIdentityAtAll() {
        // No action policy configured: the revalidation gate is skipped entirely, so an ordinary
        // action's behavior - and cost - is completely unchanged by this framework's TOCTOU
        // hardening, even for a target that could not prove its own identity.
        IActionBackend backend = mock(IActionBackend.class);
        IElement target = element(false);

        ActionResult<Void> result =
                new DefaultActionBuilder(context(backend)).click(target).execute();

        assertThat(result.success()).isTrue();
        verify(backend, times(1)).click(target);
        verify(target, never()).isStillTheOriginallyResolvedTarget();
    }

    @Test
    void planAllowedByItsSnapshotStillBlocksTheBackendWhenTheTargetIsReplacedBeforeExecute() {
        // Mirrors the "PLAN ALLOW + target replaced before execute => replacement backend 0" row
        // of the governed-execution plan matrix: the plan-time snapshot saw ALLOW against a target
        // that could still prove its identity, but by the time execute() actually runs, the exact
        // same target object can no longer prove it - simulating a live DOM replacement discovered
        // between plan() and execute().
        IActionBackend backend = mock(IActionBackend.class);
        IElement target = mock(IElement.class);
        when(target.role()).thenReturn(ElementRole.BUTTON);
        when(target.accessibleName()).thenReturn("Target");
        when(target.state())
                .thenReturn(
                        new ElementState(
                                true, true, true, false, false, false, false, false, true, true,
                                false, true));
        // plan()'s own snapshot never consults target identity at all (only execute() does,
        // immediately before the backend call), so this simulates identity already having become
        // unprovable by the time execute() performs that one, real, revalidating check.
        when(target.isStillTheOriginallyResolvedTarget()).thenReturn(false);

        IActionPlan<Void> plan =
                new DefaultActionBuilder(context(backend))
                        .click(target)
                        .policy(ctx -> PolicyDecision.allow("test.plan.identity.allowed"))
                        .plan();
        assertThat(plan.status()).isEqualTo(ActionPlanStatus.READY);

        ActionResult<Void> result = plan.execute();

        assertThat(result.success()).isFalse();
        assertThat(result.executionMode()).isEqualTo(ActionExecutionMode.NOT_EXECUTED);
        assertThat(result.failure().orElseThrow().type())
                .isEqualTo(ActionFailureType.TARGET_CHANGED);
        verify(backend, never()).click(target);
    }

    @Test
    void concurrentPlanExecutionReachesTheBackendAtMostOnce() throws InterruptedException {
        IActionBackend backend = mock(IActionBackend.class);
        IElement target = element(true);
        IActionPlan<Void> plan = new DefaultActionBuilder(context(backend)).click(target).plan();

        int threadCount = 8;
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger rejectedCount = new AtomicInteger();
        Thread[] threads = new Thread[threadCount];
        for (int i = 0; i < threadCount; i++) {
            threads[i] =
                    new Thread(
                            () -> {
                                ready.countDown();
                                try {
                                    start.await();
                                } catch (InterruptedException interrupted) {
                                    Thread.currentThread().interrupt();
                                    return;
                                }
                                try {
                                    plan.execute();
                                    successCount.incrementAndGet();
                                } catch (IllegalStateException alreadyExecuted) {
                                    rejectedCount.incrementAndGet();
                                }
                            });
            threads[i].start();
        }
        ready.await();
        start.countDown();
        for (Thread thread : threads) {
            thread.join();
        }

        assertThat(successCount.get()).isEqualTo(1);
        assertThat(rejectedCount.get()).isEqualTo(threadCount - 1);
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

    private static IElement element(boolean stillOriginallyResolvedTarget) {
        IElement element = mock(IElement.class);
        when(element.role()).thenReturn(ElementRole.BUTTON);
        when(element.accessibleName()).thenReturn("Target");
        when(element.state())
                .thenReturn(
                        new ElementState(
                                true, true, true, false, false, false, false, false, true, true,
                                false, true));
        when(element.isStillTheOriginallyResolvedTarget())
                .thenReturn(stillOriginallyResolvedTarget);
        return element;
    }
}
