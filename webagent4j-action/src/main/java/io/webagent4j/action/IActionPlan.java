package io.webagent4j.action;

import io.webagent4j.verification.VerificationResult;
import io.webagent4j.verification.VerificationType;
import java.util.List;
import java.util.Optional;

/**
 * Immutable, backend-neutral, side-effect-free preview of one action pipeline.
 *
 * <p>{@link IPreparedAction#plan()} runs the exact same deterministic target resolution and
 * precondition evaluation used by {@link IPreparedAction#execute()} and {@link
 * IPreparedAction#dryRun()}, but it never invokes the backend: building a plan never clicks, types,
 * submits, navigates, or otherwise touches the page. A plan is {@link ActionPlanStatus#READY} only
 * when the semantic target resolved to a single unambiguous candidate and every precondition passed
 * at the moment {@code plan()} was called; otherwise it is {@link ActionPlanStatus#BLOCKED} with a
 * structured {@link #failure()} reusing the same {@link ActionFailureType} taxonomy as {@link
 * ActionResult}.
 *
 * <p>A plan is a snapshot, not a guarantee. Page state can change between {@code plan()} and {@link
 * #execute()}, so {@code execute()} never trusts the snapshot: it revalidates target resolution,
 * ambiguity, and preconditions from scratch before any backend side effect. This means a plan can
 * never cause the wrong element to be acted on.
 *
 * <p>The planning data captured above is immutable, but a plan's execution lifecycle is not: an
 * {@code IActionPlan} may be executed at most once. This matters because a plan can represent a
 * non-idempotent operation (submit an order, delete an account, pay, confirm a transfer), and a
 * caller or future agent accidentally calling {@code execute()} twice must never be able to produce
 * two side effects. The single-use guard is thread-safe: only one concurrent caller of {@link
 * #execute()} ever reaches the backend, and every other call - concurrent or sequential, before or
 * after the first one returns or throws - fails with {@link IllegalStateException} without invoking
 * the backend again. The first call counts as the one attempt even if it fails.
 *
 * <p>An {@code IActionPlan} is obtained only through {@link IPreparedAction#plan()}; there is
 * intentionally no public way to construct one directly. Every field is derived from a real
 * resolution and precondition pass over live page state and pairs with a single-use execution
 * guard, so a hand-built instance could misrepresent that pass or bypass the guard.
 *
 * @param <R> the result type produced by {@link #execute()}
 */
public interface IActionPlan<R> {

    /**
     * Returns the correlation identifier for this plan. If {@link #execute()} is called, the
     * returned {@code ActionResult.actionId()} is always equal to this value, so a result can be
     * traced back to the plan that produced it even though {@code execute()} revalidates everything
     * from scratch.
     */
    ActionId actionId();

    /** Returns the planned operation category. */
    ActionType actionType();

    /** Returns the conservative execution retry classification of the planned operation. */
    ActionIdempotency idempotency();

    /** Returns the broad side-effect category of the planned operation. */
    ActionSideEffect sideEffect();

    /** Returns whether this plan is safe to execute. */
    ActionPlanStatus status();

    /** Returns a safe, non-secret description of the resolved or attempted target. */
    String targetDescription();

    /** Returns the precondition results evaluated while building this plan. */
    List<VerificationResult> preconditions();

    /**
     * Returns the categories of postconditions configured for this action. Postconditions are never
     * evaluated while planning, since doing so would require the backend side effect to have
     * already happened.
     */
    List<VerificationType> expectedPostconditions();

    /** Returns the structured reason this plan is blocked, if any. */
    Optional<ActionFailure> failure();

    /** Returns safe, non-secret diagnostics for this plan. */
    ActionDiagnostics diagnostics();

    /** Returns whether this plan resolved cleanly and can be executed. */
    boolean ready();

    /**
     * Executes the planned action pipeline from scratch.
     *
     * <p>This never trusts the snapshot captured by {@code plan()}: target resolution, ambiguity
     * detection, and preconditions are all revalidated against current page state before any
     * backend side effect. A plan that was {@link ActionPlanStatus#READY} when built can still fail
     * here if page state changed since; a plan that was {@link ActionPlanStatus#BLOCKED} can still
     * succeed if the blocking condition cleared.
     *
     * <p>This may be called at most once per {@code IActionPlan} instance, and the backend executes
     * at most once as a result. A second call - even after the first one failed - throws {@link
     * IllegalStateException} instead of invoking the backend again; build a new plan with {@code
     * plan()} to try again.
     *
     * @throws IllegalStateException if this plan has already been executed
     */
    ActionResult<R> execute();
}
