package io.webagent4j.action;

import io.webagent4j.common.RetryPolicy;
import io.webagent4j.dom.IElement;
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
}
