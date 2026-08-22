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
    void measuresStrategyDurationWithTheInjectedMonotonicClock() {
        IElement element = LocatorTestFixtures.element(ElementRole.BUTTON, "Confirm");
        FakeClock clock = new FakeClock();
        ILocatorBackend backend =
                (query, scope, config, timeout, candidateLimit) -> {
                    clock.advance(Duration.ofMillis(7));
                    return new LocatorBackendSearchResult(candidates("confirm", element), 1, false);
                };
        LocatorEngine engine = new LocatorEngine(new WaitEngine(clock, duration -> {}));

        LocatorResult result =
                engine.locate(
                        pageContext(backend),
                        LocatorDefinition.forRole(ElementRole.BUTTON).named("Confirm"));

        assertThat(result.diagnostics().strategiesExecuted())
                .isNotEmpty()
                .allSatisfy(
                        execution ->
                                assertThat(execution.duration()).isEqualTo(Duration.ofMillis(7)));
    }

    @Test
    void aNeverSatisfiedWaitTimesOutByFakeTimeInsteadOfBusyLoopingOnRealWallClockTime() {
        // A backend that never has a match: the only way this wait can ever end is the
        // WaitBudget's deadline expiring.
        ILocatorBackend neverFound =
                (query, scope, config, timeout, candidateLimit) ->
                        new LocatorBackendSearchResult(List.of(), 0, false);
        FakeClock clock = new FakeClock();
        AdvancingSleeper sleeper = new AdvancingSleeper(clock, () -> {});
        LocatorEngine engine = new LocatorEngine(new WaitEngine(clock, sleeper));

        long wallClockStartNanos = System.nanoTime();
        assertThatExceptionOfType(LocatorNotFoundException.class)
                .isThrownBy(
                        () ->
                                engine.locateSingle(
                                        pageContext(neverFound),
                                        LocatorDefinition.forRole(ElementRole.BUTTON)
                                                .named("Confirm")));
        Duration realWallClockElapsed = Duration.ofNanos(System.nanoTime() - wallClockStartNanos);

        // The fixture's 5-second configured timeout and 25ms polling interval never actually
        // sleep in real time - AdvancingSleeper only advances the fake clock - so a correctly
        // wired LocatorEngine finishes in a few milliseconds of genuine wall-clock time. If
        // LocatorEngine started its WaitBudget against a real system clock instead of
        // waitEngine.clock() (the fake clock this engine was built with), that budget would
        // never see the fake clock's advances and would never expire on its own: the engine
        // would busy-loop, actually consuming real wall-clock time, until the real clock itself
        // happened to pass 5 seconds. This bound is what turns that regression into a fast,
        // deterministic test failure instead of a merely slow one.
        assertThat(realWallClockElapsed).isLessThan(Duration.ofSeconds(1));
        // Every attempt found nothing, so every attempt slept - proving the fake clock's
        // advances (not real elapsed time) are what actually drove this wait to its deadline.
        assertThat(sleeper.sleepCount()).isGreaterThan(1);
    }

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

    /**
     * CASE A: the target is found on every single poll - it never disappears - but the requested
     * stability window (300ms) is longer than the timeout budget (100ms), so the wait can never
     * legitimately succeed. Regression coverage for the {@code LocatorEngine#resolve()} bug where a
     * {@code TIMED_OUT} {@link io.webagent4j.wait.WaitResult}'s last-observed sample - satisfied
     * for that one poll, but never stable long enough - was returned as though the wait had
     * actually succeeded.
     */
    @Test
    void aCandidateThatIsAlwaysPresentButNeverReachesStabilityBeforeTheTimeoutEndsAsNotFound() {
        IElement element = LocatorTestFixtures.element(ElementRole.BUTTON, "Confirm");
        ILocatorBackend alwaysPresent =
                (query, scope, config, timeout, candidateLimit) ->
                        new LocatorBackendSearchResult(candidates("confirm", element), 1, false);
        FakeClock clock = new FakeClock();
        AdvancingSleeper sleeper = new AdvancingSleeper(clock, () -> {});
        LocatorEngine engine = new LocatorEngine(new WaitEngine(clock, sleeper));

        assertThatExceptionOfType(LocatorNotFoundException.class)
                .isThrownBy(
                        () ->
                                engine.locate(
                                        pageContext(alwaysPresent, Duration.ofMillis(100)),
                                        LocatorDefinition.forRole(ElementRole.BUTTON)
                                                .named("Confirm")
                                                .stableFor(Duration.ofMillis(300))))
                .satisfies(
                        failure -> {
                            assertThat(failure.status()).isEqualTo(LocatorResolutionStatus.TIMEOUT);
                            assertThat(failure.diagnostics()).isPresent();
                            assertThat(failure.diagnostics().orElseThrow().reachedLimits())
                                    .contains(LocatorDiagnostics.BudgetLimit.TIMEOUT);
                        });
    }

    /**
     * CASE B: present, present, absent (resets the stability window), present, present, timeout -
     * by the time the timeout fires, the candidate has again been observed for two consecutive
     * polls but never for the full 300ms stability window the reset restarted. The final poll is
     * still a genuine {@code WaitSample.satisfied(...)} carrying a real candidate, so this is
     * exactly the shape that used to leak through as a false success.
     */
    @Test
    void
            aTransientDisappearanceThatResetsStabilityLeavesNotEnoughTimeBeforeTimeoutEndsAsNotFound() {
        IElement element = LocatorTestFixtures.element(ElementRole.BUTTON, "Confirm");
        StagedBackend backend =
                new StagedBackend(
                        List.of(
                                candidates("confirm", element),
                                candidates("confirm", element),
                                List.of(),
                                candidates("confirm", element),
                                candidates("confirm", element)));
        FakeClock clock = new FakeClock();
        AdvancingSleeper sleeper = new AdvancingSleeper(clock, backend::advance);
        LocatorEngine engine = new LocatorEngine(new WaitEngine(clock, sleeper));

        assertThatExceptionOfType(LocatorNotFoundException.class)
                .isThrownBy(
                        () ->
                                engine.locate(
                                        pageContext(backend, Duration.ofMillis(150)),
                                        LocatorDefinition.forRole(ElementRole.BUTTON)
                                                .named("Confirm")
                                                .stableFor(Duration.ofMillis(300))));
        // The wait must have actually observed the transient disappearance and the subsequent
        // reappearance, not raced past either.
        assertThat(backend.stageIndex()).isEqualTo(4);
    }

    /** CASE C: a genuinely long-enough stable sequence succeeds, carrying the real candidate. */
    @Test
    void aCandidateThatRemainsStableForTheFullWindowSucceedsWithThatCandidate() {
        IElement element = LocatorTestFixtures.element(ElementRole.BUTTON, "Confirm");
        ILocatorBackend alwaysPresent =
                (query, scope, config, timeout, candidateLimit) ->
                        new LocatorBackendSearchResult(candidates("confirm", element), 1, false);
        FakeClock clock = new FakeClock();
        AdvancingSleeper sleeper = new AdvancingSleeper(clock, () -> {});
        LocatorEngine engine = new LocatorEngine(new WaitEngine(clock, sleeper));

        LocatorResult result =
                engine.locate(
                        pageContext(alwaysPresent, Duration.ofSeconds(5)),
                        LocatorDefinition.forRole(ElementRole.BUTTON)
                                .named("Confirm")
                                .stableFor(Duration.ofMillis(100)));

        assertThat(result.element()).isSameAs(element);
    }

    /**
     * CASE D: no {@code stableFor} requested and the candidate is present on the very first poll -
     * must still succeed immediately, with no sleep at all. Guards against the fix regressing the
     * common no-stability path while gating {@code TIMED_OUT} results.
     */
    @Test
    void anImmediatelyPresentCandidateWithNoStabilityRequestedStillSucceedsOnTheFirstPoll() {
        IElement element = LocatorTestFixtures.element(ElementRole.BUTTON, "Confirm");
        ILocatorBackend alwaysPresent =
                (query, scope, config, timeout, candidateLimit) ->
                        new LocatorBackendSearchResult(candidates("confirm", element), 1, false);
        FakeClock clock = new FakeClock();
        AdvancingSleeper sleeper = new AdvancingSleeper(clock, () -> {});
        LocatorEngine engine = new LocatorEngine(new WaitEngine(clock, sleeper));

        LocatorResult result =
                engine.locate(
                        pageContext(alwaysPresent, Duration.ofSeconds(5)),
                        LocatorDefinition.forRole(ElementRole.BUTTON).named("Confirm"));

        assertThat(result.element()).isSameAs(element);
        assertThat(sleeper.sleepCount()).isZero();
    }

    /**
     * The headline invariant this fix protects: a {@link io.webagent4j.wait.WaitResult} that ends
     * {@code TIMED_OUT} may still carry a non-empty last-observed sample (preserved only for
     * diagnostics, per {@link io.webagent4j.wait.WaitSample#pending(Object)}), but {@link
     * LocatorEngine} must never let that sample stand in for a genuine success - regardless of how
     * many polls stayed satisfied on the same, never-changing candidate identity.
     */
    @Test
    void doesNotReturnLastObservedCandidateWhenStabilityTimesOut() {
        IElement element = LocatorTestFixtures.element(ElementRole.BUTTON, "Confirm");
        StagedBackend backend =
                new StagedBackend(
                        List.of(
                                candidates("confirm", element),
                                candidates("confirm", element),
                                candidates("confirm", element),
                                candidates("confirm", element),
                                candidates("confirm", element)));
        FakeClock clock = new FakeClock();
        AdvancingSleeper sleeper = new AdvancingSleeper(clock, backend::advance);
        LocatorEngine engine = new LocatorEngine(new WaitEngine(clock, sleeper));

        assertThatExceptionOfType(LocatorNotFoundException.class)
                .isThrownBy(
                        () ->
                                engine.locate(
                                        pageContext(backend, Duration.ofMillis(100)),
                                        LocatorDefinition.forRole(ElementRole.BUTTON)
                                                .named("Confirm")
                                                .stableFor(Duration.ofMillis(300))))
                .satisfies(
                        failure ->
                                assertThat(failure.status())
                                        .isEqualTo(LocatorResolutionStatus.TIMEOUT));
        // Every poll along the way was satisfied with a real, unchanging candidate - proving the
        // NotFound outcome above cannot be explained by the candidate ever having been absent.
        assertThat(backend.stageIndex()).isEqualTo(4);
    }

    private static List<LocatorBackendCandidate> candidates(String identity, IElement element) {
        return List.of(new LocatorBackendCandidate(identity, element, 0));
    }

    private static LocatorContext pageContext(ILocatorBackend backend) {
        return LocatorContext.page(backend, config(Duration.ofSeconds(5)));
    }

    private static LocatorContext pageContext(ILocatorBackend backend, Duration timeout) {
        return LocatorContext.page(backend, config(timeout));
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
        private int sleepCount;

        AdvancingSleeper(FakeClock clock, Runnable onSleep) {
            this.clock = clock;
            this.onSleep = onSleep;
        }

        @Override
        public void sleep(Duration duration) {
            sleepCount++;
            clock.advance(duration);
            onSleep.run();
        }

        int sleepCount() {
            return sleepCount;
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
