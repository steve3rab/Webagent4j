package io.webagent4j.locator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import io.webagent4j.dom.IElement;
import io.webagent4j.locator.api.ElementRole;
import io.webagent4j.locator.api.LocatorDefinition;
import io.webagent4j.wait.IMonotonicClock;
import io.webagent4j.wait.IWaitSleeper;
import io.webagent4j.wait.WaitEngine;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Proves that {@link LocatorEngine} itself - not {@code webagent4j-wait} in isolation - relies on
 * {@link WaitEngine} for stability, ambiguity, and backend-failure semantics during a wait. Uses a
 * fake monotonic clock/sleeper so the underlying {@link WaitEngine} runs its real deadline/interval
 * arithmetic without any test actually sleeping, and a {@link StagedBackend} whose results advance
 * exactly once per sleep - i.e. once per {@link WaitEngine} attempt boundary - independent of how
 * many internal locator strategies query it within a single attempt.
 */
class LocatorEngineWaitIntegrationTest {

    @Test
    void resetsTheStabilityWindowWhenTheLiveCandidateIdentityChanges() {
        IElement elementA = LocatorTestFixtures.element(ElementRole.BUTTON, "Confirm");
        IElement elementB = LocatorTestFixtures.element(ElementRole.BUTTON, "Confirm");
        StagedBackend backend =
                new StagedBackend(
                        List.of(
                                candidates("A", elementA),
                                candidates("A", elementA),
                                candidates("B", elementB),
                                candidates("B", elementB),
                                candidates("B", elementB)));
        FakeClock clock = new FakeClock();
        AdvancingSleeper sleeper = new AdvancingSleeper(clock, backend::advance);
        LocatorEngine engine = new LocatorEngine(new WaitEngine(clock, sleeper));

        LocatorResult result =
                engine.locateSingle(
                        pageContext(backend),
                        LocatorDefinition.forRole(ElementRole.BUTTON)
                                .named("Confirm")
                                .stableFor(Duration.ofMillis(250)));

        assertThat(result.element()).isSameAs(elementB);
        // Had A's two satisfied polls wrongly counted toward B's window, this would have succeeded
        // as soon as B was first observed instead of needing to remain stable on its own.
        assertThat(backend.callCount()).isGreaterThanOrEqualTo(5);
    }

    @Test
    void failsImmediatelyWhenASecondMatchingCandidateAppearsWhileWaitingForStability() {
        IElement elementA = LocatorTestFixtures.element(ElementRole.BUTTON, "Confirm");
        IElement elementB = LocatorTestFixtures.element(ElementRole.BUTTON, "Confirm");
        StagedBackend backend =
                new StagedBackend(
                        List.of(
                                List.of(),
                                candidates("A", elementA),
                                List.of(
                                        new LocatorBackendCandidate("A", elementA, 0),
                                        new LocatorBackendCandidate("B", elementB, 1))));
        FakeClock clock = new FakeClock();
        AdvancingSleeper sleeper = new AdvancingSleeper(clock, backend::advance);
        LocatorEngine engine = new LocatorEngine(new WaitEngine(clock, sleeper));

        assertThatExceptionOfType(AmbiguousLocatorException.class)
                .isThrownBy(
                        () ->
                                engine.locateSingle(
                                        pageContext(backend),
                                        LocatorDefinition.forRole(ElementRole.BUTTON)
                                                .named("Confirm")
                                                .stableFor(Duration.ofMillis(250))));
        // Ambiguity must stop the wait the moment it is observed - the backend is never consulted
        // again looking for the ambiguity to resolve itself.
        assertThat(backend.stageIndex()).isEqualTo(2);
    }

    @Test
    void propagatesAGenuineBackendFailureDuringAWaitInsteadOfReinterpretingIt() {
        RuntimeException backendFailure = new IllegalStateException("browser disconnected");
        AtomicInteger calls = new AtomicInteger();
        ILocatorBackend failingAfterOnePoll =
                (query, scope, config, timeout, candidateLimit) -> {
                    if (calls.getAndIncrement() >= 1) {
                        throw backendFailure;
                    }
                    return new LocatorBackendSearchResult(List.of(), 0, false);
                };
        FakeClock clock = new FakeClock();
        AdvancingSleeper sleeper = new AdvancingSleeper(clock, () -> {});
        LocatorEngine engine = new LocatorEngine(new WaitEngine(clock, sleeper));

        assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(
                        () ->
                                engine.locateSingle(
                                        pageContext(failingAfterOnePoll),
                                        LocatorDefinition.forRole(ElementRole.BUTTON)
                                                .named("Confirm")))
                .isSameAs(backendFailure);
    }

    private static List<LocatorBackendCandidate> candidates(String identity, IElement element) {
        return List.of(new LocatorBackendCandidate(identity, element, 0));
    }

    private static LocatorContext pageContext(ILocatorBackend backend) {
        return LocatorContext.page(backend, config(Duration.ofSeconds(5)));
    }

    private static LocatorConfig config(Duration timeout) {
        return new LocatorConfig(
                0.80, 20, timeout, true, true, 0.02, LocatorScoringConfig.defaults());
    }

    /** Fake monotonic clock advanced only by {@link AdvancingSleeper}. */
    private static final class FakeClock implements IMonotonicClock {
        private long nanos;

        @Override
        public long nanoTime() {
            return nanos;
        }

        void advance(Duration duration) {
            nanos += duration.toNanos();
        }
    }

    /** Advances the fake clock and, once per sleep, a caller-supplied stage callback. */
    private static final class AdvancingSleeper implements IWaitSleeper {
        private final FakeClock clock;
        private final Runnable onSleep;

        AdvancingSleeper(FakeClock clock, Runnable onSleep) {
            this.clock = clock;
            this.onSleep = onSleep;
        }

        @Override
        public void sleep(Duration duration) {
            clock.advance(duration);
            onSleep.run();
        }
    }

    /**
     * A backend whose results advance exactly once per {@link #advance()} call, staying on the
     * final stage once exhausted - independent of how many times {@link #find} itself is invoked
     * within one {@link WaitEngine} attempt (multiple locator strategies may each query it).
     */
    private static final class StagedBackend implements ILocatorBackend {
        private final List<List<LocatorBackendCandidate>> stages;
        private int stageIndex;
        private int callCount;

        StagedBackend(List<List<LocatorBackendCandidate>> stages) {
            this.stages = List.copyOf(stages);
        }

        void advance() {
            if (stageIndex < stages.size() - 1) {
                stageIndex++;
            }
        }

        int stageIndex() {
            return stageIndex;
        }

        int callCount() {
            return callCount;
        }

        @Override
        public LocatorBackendSearchResult find(
                LocatorBackendQuery query,
                LocatorScope scope,
                LocatorConfig config,
                Duration timeout,
                int candidateLimit) {
            callCount++;
            List<LocatorBackendCandidate> current = stages.get(stageIndex);
            return new LocatorBackendSearchResult(current, current.size(), false);
        }
    }
}
