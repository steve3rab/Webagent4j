package io.webagent4j.verification;

import io.webagent4j.wait.WaitBudget;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Evaluates ordered deterministic conditions through one centralized poller. */
public final class VerificationEngine {

    private final VerificationPoller poller;

    /** Creates an engine with the default poller. */
    public VerificationEngine() {
        this(new VerificationPoller());
    }

    /** Creates an engine using an explicit poller. */
    public VerificationEngine(VerificationPoller poller) {
        this.poller = Objects.requireNonNull(poller, "poller");
    }

    /**
     * Evaluates all conditions in encounter order, each independently allowed up to {@code
     * timeout}.
     *
     * <p>This is the original, still-supported contract: three conditions may together take up to
     * {@code 3 * timeout} in the worst case, because each one starts its own fresh budget. Callers
     * that must bound the total wait across every condition - an action's postconditions, for
     * instance - should use {@link #awaitAll(IVerificationContext, List, WaitBudget, Duration)}
     * instead, passing one budget shared across the whole list.
     */
    public List<VerificationResult> awaitAll(
            IVerificationContext context,
            List<? extends IVerification> verifications,
            Duration timeout,
            Duration interval) {
        Objects.requireNonNull(verifications, "verifications");
        List<VerificationResult> results = new ArrayList<>(verifications.size());
        for (IVerification verification : verifications) {
            results.add(poller.await(verification, context, timeout, interval));
        }
        return List.copyOf(results);
    }

    /**
     * Evaluates all conditions in encounter order, passing the exact same {@link WaitBudget}
     * instance to every one of them - no per-condition conversion to a remaining {@link Duration}
     * and back into a new budget - so they share one shrinking deadline instead of each
     * independently receiving a full, fresh timeout.
     *
     * <p>If the first condition consumes most of {@code budget}, later conditions correspondingly
     * see less of it in {@link WaitBudget#remaining()}; each one is still guaranteed at least one
     * immediate probe, per {@code WaitEngine}'s own "always probe once, even against an already
     * expired budget" contract, rather than being skipped outright.
     */
    public List<VerificationResult> awaitAll(
            IVerificationContext context,
            List<? extends IVerification> verifications,
            WaitBudget budget,
            Duration interval) {
        Objects.requireNonNull(verifications, "verifications");
        Objects.requireNonNull(budget, "budget");
        List<VerificationResult> results = new ArrayList<>(verifications.size());
        for (IVerification verification : verifications) {
            results.add(poller.await(verification, context, budget, interval));
        }
        return List.copyOf(results);
    }
}
