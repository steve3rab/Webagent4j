package io.webagent4j.action;

import io.webagent4j.verification.VerificationResult;
import io.webagent4j.verification.VerificationType;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

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
 * never cause the wrong element to be acted on, and the backend action still executes at most once
 * overall, whether an action is run directly or through {@code plan().execute()}.
 *
 * @param <R> the result type produced by {@link #execute()}
 */
public final class ActionPlan<R> {

    private final ActionId actionId;
    private final ActionType actionType;
    private final ActionIdempotency idempotency;
    private final ActionSideEffect sideEffect;
    private final ActionPlanStatus status;
    private final String targetDescription;
    private final List<VerificationResult> preconditions;
    private final List<VerificationType> expectedPostconditions;
    private final Optional<ActionFailure> failure;
    private final ActionDiagnostics diagnostics;
    private final Supplier<ActionResult<R>> executor;

    /** Builds an immutable plan snapshot; obtain one through {@link IPreparedAction#plan()}. */
    @SuppressWarnings("checkstyle:ParameterNumber")
    public ActionPlan(
            ActionId actionId,
            ActionType actionType,
            ActionIdempotency idempotency,
            ActionSideEffect sideEffect,
            ActionPlanStatus status,
            String targetDescription,
            List<VerificationResult> preconditions,
            List<VerificationType> expectedPostconditions,
            Optional<ActionFailure> failure,
            ActionDiagnostics diagnostics,
            Supplier<ActionResult<R>> executor) {
        this.actionId = Objects.requireNonNull(actionId, "actionId");
        this.actionType = Objects.requireNonNull(actionType, "actionType");
        this.idempotency = Objects.requireNonNull(idempotency, "idempotency");
        this.sideEffect = Objects.requireNonNull(sideEffect, "sideEffect");
        this.status = Objects.requireNonNull(status, "status");
        this.targetDescription = Objects.requireNonNull(targetDescription, "targetDescription");
        this.preconditions = List.copyOf(Objects.requireNonNull(preconditions, "preconditions"));
        this.expectedPostconditions =
                List.copyOf(
                        Objects.requireNonNull(expectedPostconditions, "expectedPostconditions"));
        this.failure = Objects.requireNonNull(failure, "failure");
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
        this.executor = Objects.requireNonNull(executor, "executor");
        if (status == ActionPlanStatus.READY && failure.isPresent()) {
            throw new IllegalArgumentException("a ready plan cannot carry a failure");
        }
        if (status == ActionPlanStatus.BLOCKED && failure.isEmpty()) {
            throw new IllegalArgumentException("a blocked plan must carry a failure");
        }
    }

    /** Returns the correlation identifier for this plan and, if executed, its resulting action. */
    public ActionId actionId() {
        return actionId;
    }

    /** Returns the planned operation category. */
    public ActionType actionType() {
        return actionType;
    }

    /** Returns the conservative execution retry classification of the planned operation. */
    public ActionIdempotency idempotency() {
        return idempotency;
    }

    /** Returns the broad side-effect category of the planned operation. */
    public ActionSideEffect sideEffect() {
        return sideEffect;
    }

    /** Returns whether this plan is safe to execute. */
    public ActionPlanStatus status() {
        return status;
    }

    /** Returns a safe, non-secret description of the resolved or attempted target. */
    public String targetDescription() {
        return targetDescription;
    }

    /** Returns the precondition results evaluated while building this plan. */
    public List<VerificationResult> preconditions() {
        return preconditions;
    }

    /**
     * Returns the categories of postconditions configured for this action. Postconditions are never
     * evaluated while planning, since doing so would require the backend side effect to have
     * already happened.
     */
    public List<VerificationType> expectedPostconditions() {
        return expectedPostconditions;
    }

    /** Returns the structured reason this plan is blocked, if any. */
    public Optional<ActionFailure> failure() {
        return failure;
    }

    /** Returns safe, non-secret diagnostics for this plan. */
    public ActionDiagnostics diagnostics() {
        return diagnostics;
    }

    /** Returns whether this plan resolved cleanly and can be executed. */
    public boolean ready() {
        return status == ActionPlanStatus.READY;
    }

    /**
     * Executes the planned action pipeline from scratch.
     *
     * <p>This never trusts the snapshot captured by {@code plan()}: target resolution, ambiguity
     * detection, and preconditions are all revalidated against current page state before any
     * backend side effect. A plan that was {@link ActionPlanStatus#READY} when built can still fail
     * here if page state changed since; a plan that was {@link ActionPlanStatus#BLOCKED} can still
     * succeed if the blocking condition cleared. Either way the backend action executes at most
     * once.
     */
    public ActionResult<R> execute() {
        return executor.get();
    }
}
