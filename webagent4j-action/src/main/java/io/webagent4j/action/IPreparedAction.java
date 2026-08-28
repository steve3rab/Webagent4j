package io.webagent4j.action;

import io.webagent4j.action.policy.IActionPolicy;
import io.webagent4j.common.RetryPolicy;
import io.webagent4j.dom.IElement;
import io.webagent4j.policy.network.INetworkPolicy;
import io.webagent4j.verification.IVerification;
import java.time.Duration;
import java.util.function.Predicate;

/** Fluent configuration for one selected action command. */
public interface IPreparedAction<R> {

    /** Adds a target-state predicate as an explicit precondition. */
    IPreparedAction<R> precondition(Predicate<IElement> predicate);

    /** Adds a deterministic explicit precondition. */
    IPreparedAction<R> require(IVerification verification);

    /** Adds a deterministic postcondition. */
    IPreparedAction<R> expect(IVerification verification);

    /** Compatibility convenience for URL fragment verification. */
    IPreparedAction<R> expectUrlContains(String expectedFragment);

    /** Sets the overall action and verification timeout budget. */
    IPreparedAction<R> timeout(Duration timeout);

    /** Configures safe resolution retries; non-idempotent execution is never repeated. */
    IPreparedAction<R> retry(RetryPolicy retryPolicy);

    /** Selects a semantic observation capture policy. */
    IPreparedAction<R> captureObservations(ObservationCapturePolicy policy);

    /** Enables or disables unconditional before and after observations. */
    default IPreparedAction<R> captureObservations(boolean enabled) {
        return captureObservations(
                enabled ? ObservationCapturePolicy.ALWAYS : ObservationCapturePolicy.NONE);
    }

    /** Executes the action pipeline exactly once after resolution and preconditions. */
    ActionResult<R> execute();

    /**
     * Marks this prepared action as a dry-run where the backend action is simulated but not
     * performed.
     */
    IPreparedAction<R> dryRun();

    /**
     * Runs target resolution and precondition evaluation without any backend side effect and
     * returns an immutable, inspectable {@link IActionPlan}.
     *
     * <p>Unlike {@link #execute()} and {@link #dryRun()}, calling {@code plan()} never advances the
     * action: it only produces a snapshot. The plan can later be inspected or executed with {@link
     * IActionPlan#execute()}, which always revalidates resolution and preconditions before any
     * backend action, so a stale plan can never cause the wrong element to be acted on; that
     * revalidated execution shares the plan's {@link IActionPlan#actionId()}, so a result can
     * always be correlated back to the plan that produced it.
     *
     * <p>{@code dryRun()} and {@code plan()} are mutually exclusive terminal modes on the same
     * prepared action: {@code dryRun().execute()} validates and returns an {@link ActionResult}
     * without ever producing a plan, while {@code plan()} always produces a plan whose {@code
     * execute()} performs the real action regardless of any earlier {@code dryRun()} call. Calling
     * {@code plan()} after {@code dryRun()} on the same prepared action throws {@link
     * IllegalStateException} rather than silently picking one interpretation.
     *
     * @throws IllegalStateException if {@link #dryRun()} was already called on this prepared action
     */
    IActionPlan<R> plan();

    /**
     * Configures an {@link IActionPolicy} that must {@code ALLOW} this action before its backend
     * side effect is invoked - by {@link #execute()} directly, by {@link #dryRun()}'s validation,
     * and by the real execution behind {@link IActionPlan#execute()} alike. A {@code DENY}, a
     * thrown exception, or any other evaluation failure prevents the backend from ever being
     * called; see {@code docs/governed-execution.md} for the exact pipeline position and failure
     * shape.
     *
     * <p>The default implementation always throws {@link UnsupportedOperationException} - only an
     * {@link IPreparedAction} implementation that actually enforces this configuration may override
     * it; a caller must never be able to configure a policy that is silently ignored.
     *
     * @throws NullPointerException if {@code policy} is {@code null}
     * @throws IllegalStateException if a policy is already configured on this prepared action
     */
    default IPreparedAction<R> policy(IActionPolicy policy) {
        throw new UnsupportedOperationException(
                "policy(...) is not supported by this IPreparedAction implementation");
    }

    /**
     * Configures an {@link INetworkPolicy} that must {@code ALLOW} this action's network
     * destination - checked before the backend call, and, for a {@code NAVIGATE} action, checked
     * again after navigation against the final URL, since a browser's own internal redirects cannot
     * be intercepted mid-flight. Only {@link ActionType#NAVIGATE} has a network destination known
     * before its backend call is made; configuring this on any other action type is rejected
     * immediately rather than silently ignored, since the framework cannot honestly enforce a
     * network policy it cannot actually evaluate before that action's backend call.
     *
     * <p>The default implementation always throws {@link UnsupportedOperationException} for the
     * same reason {@link #policy(IActionPolicy)}'s does.
     *
     * @throws NullPointerException if {@code networkPolicy} is {@code null}
     * @throws IllegalStateException if a network policy is already configured on this prepared
     *     action
     * @throws UnsupportedOperationException if this action is not a {@code NAVIGATE} action (thrown
     *     by an enforcing implementation; the inherited default throws it unconditionally)
     */
    default IPreparedAction<R> networkPolicy(INetworkPolicy networkPolicy) {
        throw new UnsupportedOperationException(
                "networkPolicy(...) is not supported by this IPreparedAction implementation");
    }
}
