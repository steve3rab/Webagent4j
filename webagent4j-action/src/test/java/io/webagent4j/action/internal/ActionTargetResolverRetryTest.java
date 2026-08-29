package io.webagent4j.action.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.webagent4j.action.ActionIdempotency;
import io.webagent4j.action.ActionSideEffect;
import io.webagent4j.action.ActionType;
import io.webagent4j.common.RetryPolicy;
import io.webagent4j.dom.ElementState;
import io.webagent4j.dom.IElement;
import io.webagent4j.locator.AmbiguousLocatorException;
import io.webagent4j.locator.LocatorNotFoundException;
import io.webagent4j.locator.api.IElementReference;
import io.webagent4j.wait.IMonotonicClock;
import io.webagent4j.wait.WaitBudget;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/**
 * Proves {@link ActionTargetResolver}'s retry classification directly: only a typed "not found"
 * outcome (including a resolved-but-detached element) is retried. Ambiguity and any other failure -
 * a genuine backend/runtime error in particular - end resolution on the first attempt, and no
 * attempt is made once the shared {@link WaitBudget} is already expired.
 */
class ActionTargetResolverRetryTest {

    private final ActionTargetResolver resolver = new ActionTargetResolver();
    private final RetryPolicy policy =
            new RetryPolicy(3, Duration.ofMillis(1), 1.0, Duration.ofMillis(5));

    @Test
    void retriesOnlyTypedNotFoundUntilTheTargetAppears() {
        IElement target = presentElement();
        AtomicInteger attempts = new AtomicInteger();
        ActionCommand<Void> command =
                command(
                        () -> {
                            if (attempts.incrementAndGet() < 3) {
                                throw new LocatorNotFoundException("not yet");
                            }
                            return target;
                        });

        IElement resolved = resolver.resolve(command, policy, freshBudget());

        assertThat(resolved).isSameAs(target);
        assertThat(attempts.get()).isEqualTo(3);
    }

    @Test
    void retriesADetachedResolvedElementTheSameAsNotFound() {
        IElement detached = mock(IElement.class);
        when(detached.state())
                .thenReturn(
                        new ElementState(
                                false, false, false, false, false, false, false, false, false,
                                false, false, false));
        IElement target = presentElement();
        AtomicInteger attempts = new AtomicInteger();
        ActionCommand<Void> command =
                command(() -> attempts.incrementAndGet() < 2 ? detached : target);

        IElement resolved = resolver.resolve(command, policy, freshBudget());

        assertThat(resolved).isSameAs(target);
        assertThat(attempts.get()).isEqualTo(2);
    }

    @Test
    void stopsImmediatelyOnAmbiguityWithoutRetrying() {
        AtomicInteger attempts = new AtomicInteger();
        ActionCommand<Void> command =
                command(
                        () -> {
                            attempts.incrementAndGet();
                            throw new AmbiguousLocatorException("ambiguous");
                        });

        assertThatExceptionOfType(AmbiguousLocatorException.class)
                .isThrownBy(() -> resolver.resolve(command, policy, freshBudget()));
        assertThat(attempts.get()).isEqualTo(1);
    }

    @Test
    void stopsImmediatelyOnABackendFailureWithoutRetrying() {
        RuntimeException backendFailure = new IllegalStateException("backend disconnected");
        AtomicInteger attempts = new AtomicInteger();
        ActionCommand<Void> command =
                command(
                        () -> {
                            attempts.incrementAndGet();
                            throw backendFailure;
                        });

        assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(() -> resolver.resolve(command, policy, freshBudget()))
                .isSameAs(backendFailure);
        assertThat(attempts.get()).isEqualTo(1);
    }

    @Test
    void stopsAtABackendFailureAfterAnEarlierNotFoundInsteadOfContinuingToRetry() {
        RuntimeException backendFailure = new IllegalStateException("backend disconnected");
        AtomicInteger attempts = new AtomicInteger();
        ActionCommand<Void> command =
                command(
                        () -> {
                            if (attempts.incrementAndGet() == 1) {
                                throw new LocatorNotFoundException("not yet");
                            }
                            throw backendFailure;
                        });

        assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(() -> resolver.resolve(command, policy, freshBudget()))
                .isSameAs(backendFailure);
        assertThat(attempts.get()).isEqualTo(2);
    }

    @Test
    void stopsRetryingOnceTheBudgetIsAlreadyExpiredEvenWithAttemptsRemaining() {
        AtomicInteger attempts = new AtomicInteger();
        ActionCommand<Void> command =
                command(
                        () -> {
                            attempts.incrementAndGet();
                            throw new LocatorNotFoundException("never");
                        });
        AtomicLong now = new AtomicLong();
        WaitBudget alreadyExpired = WaitBudget.start(Duration.ofMillis(100), now::get);
        now.set(Duration.ofMillis(100).toNanos());

        assertThatExceptionOfType(LocatorNotFoundException.class)
                .isThrownBy(() -> resolver.resolve(command, policy, alreadyExpired));
        // policy allows 3 attempts, but the budget is already expired after the first, so no
        // further attempt - and no sleep - is made.
        assertThat(attempts.get()).isEqualTo(1);
    }

    private static WaitBudget freshBudget() {
        return WaitBudget.start(Duration.ofSeconds(5), IMonotonicClock.systemClock());
    }

    private static ActionCommand<Void> command(IElementReference<IElement> target) {
        return new ActionCommand<>(
                ActionType.CLICK,
                ActionIdempotency.IDEMPOTENT,
                ActionSideEffect.LOCAL_PAGE_STATE,
                target,
                (backend, resolvedTarget) -> null,
                null,
                java.util.Optional.empty());
    }

    private static IElement presentElement() {
        IElement element = mock(IElement.class);
        when(element.state())
                .thenReturn(
                        new ElementState(
                                true, true, true, false, false, false, false, false, true, true,
                                false, true));
        return element;
    }
}
