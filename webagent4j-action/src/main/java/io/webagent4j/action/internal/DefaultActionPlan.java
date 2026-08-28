package io.webagent4j.action.internal;

import io.webagent4j.action.ActionDecisionEntry;
import io.webagent4j.action.ActionDiagnostics;
import io.webagent4j.action.ActionFailure;
import io.webagent4j.action.ActionId;
import io.webagent4j.action.ActionIdempotency;
import io.webagent4j.action.ActionPlanStatus;
import io.webagent4j.action.ActionResult;
import io.webagent4j.action.ActionSideEffect;
import io.webagent4j.action.ActionType;
import io.webagent4j.action.IActionPlan;
import io.webagent4j.verification.VerificationResult;
import io.webagent4j.verification.VerificationType;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * Sole {@link IActionPlan} implementation. Package-private by design: only {@link ActionExecutor}
 * builds one, from a real resolution and precondition pass over live page state, so there is no way
 * to fabricate a plan that misrepresents that pass or bypasses the single-use execution guard.
 */
final class DefaultActionPlan<R> implements IActionPlan<R> {

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
    private final List<ActionDecisionEntry> policyDecisions;
    private final Supplier<ActionResult<R>> executor;
    private final AtomicBoolean executionStarted = new AtomicBoolean();

    @SuppressWarnings("checkstyle:ParameterNumber")
    DefaultActionPlan(
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
            List<ActionDecisionEntry> policyDecisions,
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
        this.policyDecisions =
                List.copyOf(Objects.requireNonNull(policyDecisions, "policyDecisions"));
        this.executor = Objects.requireNonNull(executor, "executor");
        if (status == ActionPlanStatus.READY && failure.isPresent()) {
            throw new IllegalArgumentException("a ready plan cannot carry a failure");
        }
        if (status == ActionPlanStatus.BLOCKED && failure.isEmpty()) {
            throw new IllegalArgumentException("a blocked plan must carry a failure");
        }
    }

    @Override
    public ActionId actionId() {
        return actionId;
    }

    @Override
    public ActionType actionType() {
        return actionType;
    }

    @Override
    public ActionIdempotency idempotency() {
        return idempotency;
    }

    @Override
    public ActionSideEffect sideEffect() {
        return sideEffect;
    }

    @Override
    public ActionPlanStatus status() {
        return status;
    }

    @Override
    public String targetDescription() {
        return targetDescription;
    }

    @Override
    public List<VerificationResult> preconditions() {
        return preconditions;
    }

    @Override
    public List<VerificationType> expectedPostconditions() {
        return expectedPostconditions;
    }

    @Override
    public Optional<ActionFailure> failure() {
        return failure;
    }

    @Override
    public ActionDiagnostics diagnostics() {
        return diagnostics;
    }

    @Override
    public boolean ready() {
        return status == ActionPlanStatus.READY;
    }

    @Override
    public List<ActionDecisionEntry> policyDecisions() {
        return policyDecisions;
    }

    @Override
    public ActionResult<R> execute() {
        if (!executionStarted.compareAndSet(false, true)) {
            throw new IllegalStateException("ActionPlan has already been executed");
        }
        return executor.get();
    }
}
