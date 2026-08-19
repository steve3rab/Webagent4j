package io.webagent4j.action.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import io.webagent4j.action.ActionResult;
import io.webagent4j.action.ActionStatus;
import io.webagent4j.action.IActionBackend;
import io.webagent4j.action.IActionContext;
import io.webagent4j.dom.ElementState;
import io.webagent4j.dom.IElement;
import io.webagent4j.locator.api.ElementRole;
import io.webagent4j.verification.IVerification;
import io.webagent4j.verification.VerificationResult;
import io.webagent4j.verification.VerificationType;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Proves that an action's postconditions share one deadline instead of each independently receiving
 * a full, fresh timeout - the "3 conditions x 5s = 15s" bug this mission's Wait Engine migration
 * was meant to close (see {@code io.webagent4j.verification.VerificationSharedBudgetTest} for the
 * same proof one layer down, with fake time).
 */
class ActionSharedDeadlineTest {

    @Test
    void twoNeverSatisfiedPostconditionsTogetherConsumeOnlyOneActionTimeout() {
        IActionBackend backend = mock(IActionBackend.class);
        IElement target = element(true);
        AtomicInteger secondEvaluations = new AtomicInteger();
        IVerification neverSucceeds = neverSucceeds("first");
        IVerification countingNeverSucceeds =
                current -> {
                    secondEvaluations.incrementAndGet();
                    return new VerificationResult(
                            false,
                            VerificationType.CUSTOM,
                            "second",
                            "expected",
                            "actual",
                            Duration.ZERO,
                            false);
                };
        Duration timeout = Duration.ofMillis(80);

        Instant started = Instant.now();
        ActionResult<Void> result =
                new DefaultActionBuilder(context(backend))
                        .click(target)
                        .expect(neverSucceeds)
                        .expect(countingNeverSucceeds)
                        .timeout(timeout)
                        .execute();
        Duration elapsedWallClock = Duration.between(started, Instant.now());

        assertThat(result.status()).isEqualTo(ActionStatus.TIMEOUT);
        // A budget-per-condition bug would let total wall-clock time approach 2 * timeout (each
        // postcondition independently exhausting its own full allowance). Sharing one budget keeps
        // it close to a single timeout instead - generous slack accounts for scheduling noise, but
        // nowhere near double.
        assertThat(elapsedWallClock).isLessThan(timeout.multipliedBy(2));
        // The first condition alone was enough to exhaust the shared budget, so the second barely
        // gets evaluated at all - certainly nowhere near as many times as it would with its own
        // fresh timeout.
        assertThat(secondEvaluations.get()).isLessThan(5);
    }

    private static IVerification neverSucceeds(String description) {
        return current ->
                new VerificationResult(
                        false,
                        VerificationType.CUSTOM,
                        description,
                        "satisfied",
                        "never",
                        Duration.ZERO,
                        false);
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

    private static IElement element(boolean enabled) {
        IElement element = mock(IElement.class);
        org.mockito.Mockito.when(element.role()).thenReturn(ElementRole.BUTTON);
        org.mockito.Mockito.when(element.accessibleName()).thenReturn("Target");
        org.mockito.Mockito.when(element.state())
                .thenReturn(
                        new ElementState(
                                true, true, enabled, false, false, false, false, false, true,
                                enabled, false, true));
        return element;
    }
}
