package io.webagent4j.action.internal;

import io.webagent4j.action.ActionDiagnostics;
import io.webagent4j.action.ActionEvent;
import io.webagent4j.action.ActionExecutionMode;
import io.webagent4j.action.ActionFailure;
import io.webagent4j.action.ActionFailureType;
import io.webagent4j.action.ActionId;
import io.webagent4j.action.ActionPlanStatus;
import io.webagent4j.action.ActionResult;
import io.webagent4j.action.ActionStage;
import io.webagent4j.action.ActionStatus;
import io.webagent4j.action.ActionTimings;
import io.webagent4j.action.IActionContext;
import io.webagent4j.action.IActionPlan;
import io.webagent4j.action.ObservationCapturePolicy;
import io.webagent4j.action.StabilizationResult;
import io.webagent4j.action.policy.ActionPolicyContext;
import io.webagent4j.action.policy.ActionPolicyMode;
import io.webagent4j.action.policy.IActionPolicy;
import io.webagent4j.common.LocatorFailureClassifier;
import io.webagent4j.dom.IElement;
import io.webagent4j.locator.AmbiguousLocatorException;
import io.webagent4j.locator.LocatorDiagnosticsRenderer;
import io.webagent4j.locator.LocatorNotFoundException;
import io.webagent4j.observation.Observation;
import io.webagent4j.observation.ObservationDiff;
import io.webagent4j.policy.PolicyDecision;
import io.webagent4j.policy.network.INetworkPolicy;
import io.webagent4j.policy.network.NetworkCheckPhase;
import io.webagent4j.policy.network.NetworkDestination;
import io.webagent4j.policy.network.NetworkPolicyContext;
import io.webagent4j.policy.network.NetworkRequestKind;
import io.webagent4j.verification.IVerification;
import io.webagent4j.verification.VerificationEngine;
import io.webagent4j.verification.VerificationInterruptedException;
import io.webagent4j.verification.VerificationResult;
import io.webagent4j.verification.VerificationType;
import io.webagent4j.wait.IMonotonicClock;
import io.webagent4j.wait.WaitBudget;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/** Executes the ordered resolve, validate, execute-once, stabilize, observe, verify pipeline. */
final class ActionExecutor {

    private final IMonotonicClock clock;
    private final VerificationEngine verificationEngine;

    ActionExecutor() {
        this(IMonotonicClock.systemClock());
    }

    ActionExecutor(IMonotonicClock clock) {
        this(clock, new VerificationEngine());
    }

    /**
     * Creates an executor with an explicit clock and postcondition-verification engine, for
     * deterministic fake-time tests that must prove the shared action deadline/budget invariant
     * without ever depending on real elapsed wall-clock time. {@code clock} must be the same
     * instance backing {@code verificationEngine}'s own polling, or the shared {@link WaitBudget}
     * this executor starts and the engine's deadline arithmetic will disagree about elapsed time.
     */
    ActionExecutor(IMonotonicClock clock, VerificationEngine verificationEngine) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.verificationEngine = Objects.requireNonNull(verificationEngine, "verificationEngine");
    }

    <R> ActionResult<R> execute(
            IActionContext context, ActionCommand<R> command, ActionExecutionConfig config) {
        return execute(context, command, config, ActionId.create());
    }

    /**
     * Executes the pipeline under an explicit correlation identifier, so a result produced through
     * {@link IActionPlan#execute()} can carry the same {@link ActionId} as the {@link IActionPlan}
     * it came from.
     */
    <R> ActionResult<R> execute(
            IActionContext context,
            ActionCommand<R> command,
            ActionExecutionConfig config,
            ActionId actionId) {
        long startedNanos = clock.nanoTime();
        WaitBudget budget = WaitBudget.start(config.options().timeout(), clock);
        List<ActionEvent> events = new ArrayList<>();
        events.add(
                event(actionId, command, ActionStage.ACTION_STARTED, "started", "", startedNanos));
        IElement target;
        long resolutionStartedNanos = clock.nanoTime();
        events.add(
                event(
                        actionId,
                        command,
                        ActionStage.TARGET_RESOLUTION_STARTED,
                        "started",
                        "",
                        startedNanos));
        try {
            target =
                    new ActionTargetResolver()
                            .resolve(command, config.options().resolutionRetry(), budget);
        } catch (ActionInterruptedException failure) {
            Thread.currentThread().interrupt();
            return failed(
                    context,
                    command,
                    config,
                    actionId,
                    startedNanos,
                    events,
                    elapsedSince(resolutionStartedNanos),
                    Duration.ZERO,
                    Duration.ZERO,
                    Duration.ZERO,
                    List.of(),
                    List.of(),
                    null,
                    null,
                    "",
                    ActionFailureType.INTERRUPTED,
                    ActionExecutionMode.NOT_EXECUTED,
                    ActionStatus.CANCELLED,
                    "Action target resolution was interrupted",
                    failure);
        } catch (RuntimeException failure) {
            return failed(
                    context,
                    command,
                    config,
                    actionId,
                    startedNanos,
                    events,
                    elapsedSince(resolutionStartedNanos),
                    Duration.ZERO,
                    Duration.ZERO,
                    Duration.ZERO,
                    List.of(),
                    List.of(),
                    null,
                    null,
                    renderLocatorDiagnostics(failure),
                    classifyResolution(failure),
                    ActionExecutionMode.NOT_EXECUTED,
                    ActionStatus.EXECUTION_FAILED,
                    "Action target could not be resolved",
                    failure);
        }
        Duration resolutionDuration = elapsedSince(resolutionStartedNanos);
        String targetDescription = describe(target);
        events.add(
                event(
                        actionId,
                        command,
                        ActionStage.TARGET_RESOLVED,
                        "resolved",
                        targetDescription,
                        startedNanos));

        Observation before = captureBefore(context, config.options().observationCapture());
        long preconditionStartedNanos = clock.nanoTime();
        events.add(
                event(
                        actionId,
                        command,
                        ActionStage.PRECONDITION_STARTED,
                        "started",
                        targetDescription,
                        startedNanos));
        List<VerificationResult> preconditions =
                new PreconditionEvaluator()
                        .evaluate(command.type(), target, context, config.preconditions());
        Duration preconditionDuration = elapsedSince(preconditionStartedNanos);
        if (preconditions.stream().anyMatch(result -> !result.success())) {
            events.add(
                    event(
                            actionId,
                            command,
                            ActionStage.ACTION_FAILED,
                            "precondition-failed",
                            targetDescription,
                            startedNanos));
            return failed(
                    context,
                    command,
                    config,
                    actionId,
                    startedNanos,
                    events,
                    resolutionDuration,
                    preconditionDuration,
                    Duration.ZERO,
                    Duration.ZERO,
                    preconditions,
                    List.of(),
                    before,
                    target,
                    "",
                    ActionFailureType.PRECONDITION_FAILED,
                    ActionExecutionMode.NOT_EXECUTED,
                    ActionStatus.PRECONDITION_FAILED,
                    "An action precondition was not satisfied",
                    null);
        }
        events.add(
                event(
                        actionId,
                        command,
                        ActionStage.PRECONDITION_COMPLETED,
                        "succeeded",
                        targetDescription,
                        startedNanos));

        // Authorization happens as late as practical - immediately before any backend-facing
        // decision (the dry-run short circuit below, or the real backend call further down) -
        // never right after resolution, so the window between "what was checked" and "what
        // actually runs" stays as small as this single-resolution pipeline allows. A DENY, a
        // thrown exception, and a malformed (null) decision are all treated identically: fail
        // closed, backend never invoked, ActionExecutionMode.NOT_EXECUTED.
        if (config.actionPolicy().isPresent()) {
            ActionPolicyMode mode =
                    config.dryRun() ? ActionPolicyMode.DRY_RUN : ActionPolicyMode.EXECUTE;
            ActionResult<R> denied =
                    authorizeAction(
                            context,
                            command,
                            config,
                            actionId,
                            startedNanos,
                            events,
                            resolutionDuration,
                            preconditionDuration,
                            preconditions,
                            before,
                            target,
                            targetDescription,
                            mode);
            if (denied != null) {
                return denied;
            }
        }

        // Network-destination governance is independent of action authorization above: both
        // gates must pass. Only NAVIGATE has a network destination knowable before its backend
        // call, so this only ever runs for that action type - see
        // IPreparedAction#networkPolicy's rejection of every other action type at configuration
        // time.
        if (command.type() == io.webagent4j.action.ActionType.NAVIGATE
                && config.networkPolicy().isPresent()) {
            ActionResult<R> networkDenied =
                    authorizeNetworkDestination(
                            context,
                            command,
                            config,
                            actionId,
                            startedNanos,
                            events,
                            resolutionDuration,
                            preconditionDuration,
                            Duration.ZERO,
                            Duration.ZERO,
                            preconditions,
                            before,
                            target,
                            command.navigationUrl().orElseThrow(),
                            NetworkCheckPhase.PRE_REQUEST);
            if (networkDenied != null) {
                return networkDenied;
            }
        }

        if (config.dryRun()) {
            // A dry-run never invokes the backend, so it never emits BACKEND_ACTION_STARTED or
            // BACKEND_ACTION_COMPLETED, and it never performs stabilization or postcondition
            // verification, which both depend on a real side effect having happened. Exactly one
            // terminal ACTION_COMPLETED event is emitted for this logical execution.
            Duration total = elapsedSince(startedNanos);
            events.add(
                    event(
                            actionId,
                            command,
                            ActionStage.ACTION_COMPLETED,
                            "dry-run-validated",
                            targetDescription,
                            startedNanos));
            return new ActionResult<>(
                    actionId,
                    command.type(),
                    ActionExecutionMode.DRY_RUN,
                    ActionStatus.SUCCESS,
                    null,
                    total,
                    new ActionTimings(
                            total,
                            resolutionDuration,
                            preconditionDuration,
                            Duration.ZERO,
                            Duration.ZERO,
                            Duration.ZERO),
                    preconditions,
                    List.of(),
                    before,
                    null,
                    null,
                    events,
                    Optional.empty(),
                    new ActionDiagnostics(targetDescription, "", Map.of("execution", "dry-run")));
        }

        if (budget.expired()) {
            // The action's global budget was already consumed by resolution and/or preconditions.
            // A backend side effect must never start after its budget has expired, and it is never
            // retried as part of this pipeline, so there is nothing left to attempt here.
            events.add(
                    event(
                            actionId,
                            command,
                            ActionStage.ACTION_FAILED,
                            "budget-expired-before-backend-action",
                            targetDescription,
                            startedNanos));
            return failed(
                    context,
                    command,
                    config,
                    actionId,
                    startedNanos,
                    events,
                    resolutionDuration,
                    preconditionDuration,
                    Duration.ZERO,
                    Duration.ZERO,
                    preconditions,
                    List.of(),
                    before,
                    target,
                    "",
                    ActionFailureType.TIMEOUT,
                    ActionExecutionMode.NOT_EXECUTED,
                    ActionStatus.TIMEOUT,
                    "Action budget expired before the backend action could be invoked",
                    null);
        }

        if (Thread.currentThread().isInterrupted()) {
            // A caller-observable interrupt raised during policy evaluation (or any point up to
            // here) must still prevent the backend from ever being invoked - identical in spirit
            // to the budget-expired check just above.
            events.add(
                    event(
                            actionId,
                            command,
                            ActionStage.ACTION_FAILED,
                            "interrupted-before-backend-action",
                            targetDescription,
                            startedNanos));
            return failed(
                    context,
                    command,
                    config,
                    actionId,
                    startedNanos,
                    events,
                    resolutionDuration,
                    preconditionDuration,
                    Duration.ZERO,
                    Duration.ZERO,
                    preconditions,
                    List.of(),
                    before,
                    target,
                    "",
                    ActionFailureType.INTERRUPTED,
                    ActionExecutionMode.NOT_EXECUTED,
                    ActionStatus.CANCELLED,
                    "Action was interrupted before the backend action could be invoked",
                    null);
        }

        // Closes the window between "the policy authorized this concrete target" and "the backend
        // side effect runs against it": an action policy's ALLOW describes a specific,
        // already-resolved target, and that authorization must never silently transfer to a
        // different element that happens to satisfy the same semantic locator by the time the
        // backend is actually invoked. Only runs when an action policy is configured (and there is
        // a concrete target to revalidate) so an ungoverned action's behavior is completely
        // unchanged - no new backend cost, no new failure mode. Shares this same action's deadline;
        // it is not given a fresh timeout of its own.
        //
        // Verification and backend execution both act through executionTarget, the same IElement
        // verifiedForExecution() just proved identity on - never through a second, independent
        // resolution presumed to find the same physical node. A backend without an atomic-handle
        // concept degrades to the previous boolean-only check; one that has it (see
        // IElement#verifiedForExecution()) closes the residual gap between checking and using.
        IElement executionTarget = target;
        boolean disposeExecutionTarget = false;
        if (config.actionPolicy().isPresent() && target != null) {
            // verifiedForExecution() is a backend extension point, not framework-owned code: a
            // RuntimeException it throws, or an outright null in place of Optional (a malformed
            // implementation violating the interface contract), must fail exactly as closed as an
            // explicit Optional.empty() - never escape this pipeline as a raw, unstructured
            // exception, and never let inability to prove identity become permission by accident.
            Optional<IElement> verified;
            RuntimeException verificationFailure = null;
            try {
                verified = target.verifiedForExecution();
            } catch (RuntimeException failure) {
                verified = null;
                verificationFailure = failure;
            }
            if (verified == null || verified.isEmpty()) {
                events.add(
                        event(
                                actionId,
                                command,
                                ActionStage.ACTION_FAILED,
                                "target-changed-before-backend-action",
                                targetDescription,
                                startedNanos));
                return failed(
                        context,
                        command,
                        config,
                        actionId,
                        startedNanos,
                        events,
                        resolutionDuration,
                        preconditionDuration,
                        Duration.ZERO,
                        Duration.ZERO,
                        preconditions,
                        List.of(),
                        before,
                        target,
                        "",
                        ActionFailureType.TARGET_CHANGED,
                        ActionExecutionMode.NOT_EXECUTED,
                        ActionStatus.EXECUTION_FAILED,
                        "The action target could not be proven unchanged immediately before"
                                + " backend execution",
                        verificationFailure);
            }
            executionTarget = verified.get();
            disposeExecutionTarget = true;
        }

        R value;
        long executionStartedNanos = clock.nanoTime();
        events.add(
                event(
                        actionId,
                        command,
                        ActionStage.BACKEND_ACTION_STARTED,
                        "started",
                        targetDescription,
                        startedNanos));
        try {
            value = command.executeBackend(context.actionBackend(), executionTarget);
        } catch (RuntimeException failure) {
            return failed(
                    context,
                    command,
                    config,
                    actionId,
                    startedNanos,
                    events,
                    resolutionDuration,
                    preconditionDuration,
                    elapsedSince(executionStartedNanos),
                    Duration.ZERO,
                    preconditions,
                    List.of(),
                    before,
                    target,
                    "",
                    classifyExecution(command, failure),
                    ActionExecutionMode.REAL,
                    ActionStatus.EXECUTION_FAILED,
                    "Backend action execution failed",
                    failure);
        } finally {
            if (disposeExecutionTarget && executionTarget instanceof AutoCloseable closeable) {
                try {
                    closeable.close();
                } catch (Exception ignored) {
                    // Best-effort cleanup only. Never replace the semantic result/failure of the
                    // backend call.
                }
            }
        }
        Duration executionDuration = elapsedSince(executionStartedNanos);
        events.add(
                event(
                        actionId,
                        command,
                        ActionStage.BACKEND_ACTION_COMPLETED,
                        "executed-once",
                        targetDescription,
                        startedNanos));

        // Post-navigation network-destination check: only reached for a NAVIGATE action after the
        // browser genuinely navigated, so a DENY here is a POLICY_VIOLATION reported with
        // ActionExecutionMode.REAL - never NOT_EXECUTED, since the navigation already happened and
        // cannot be un-navigated. This exists only because a browser's own internal redirect
        // handling cannot be intercepted mid-flight, unlike HttpCrawler's per-hop check.
        if (command.type() == io.webagent4j.action.ActionType.NAVIGATE
                && config.networkPolicy().isPresent()) {
            ActionResult<R> networkViolation =
                    authorizeNetworkDestination(
                            context,
                            command,
                            config,
                            actionId,
                            startedNanos,
                            events,
                            resolutionDuration,
                            preconditionDuration,
                            executionDuration,
                            Duration.ZERO,
                            preconditions,
                            before,
                            target,
                            context.url(),
                            NetworkCheckPhase.POST_REQUEST);
            if (networkViolation != null) {
                return networkViolation;
            }
        }

        long stabilizationStartedNanos = clock.nanoTime();
        events.add(
                event(
                        actionId,
                        command,
                        ActionStage.STABILIZATION_STARTED,
                        "started",
                        targetDescription,
                        startedNanos));
        StabilizationResult stabilization;
        try {
            stabilization = config.stabilization().await(context, budget.remaining());
        } catch (RuntimeException failure) {
            // The backend side effect has already happened by this point, so this can never be
            // reported as NOT_EXECUTED regardless of what stabilization itself did - a caller must
            // never be misled into believing it is safe to retry.
            events.add(
                    event(
                            actionId,
                            command,
                            ActionStage.ACTION_FAILED,
                            "stabilization-failed",
                            targetDescription,
                            startedNanos));
            return failed(
                    context,
                    command,
                    config,
                    actionId,
                    startedNanos,
                    events,
                    resolutionDuration,
                    preconditionDuration,
                    executionDuration,
                    elapsedSince(stabilizationStartedNanos),
                    preconditions,
                    List.of(),
                    before,
                    target,
                    "",
                    ActionFailureType.STABILIZATION_FAILED,
                    ActionExecutionMode.REAL,
                    ActionStatus.EXECUTION_FAILED,
                    "Stabilization failed after the backend action already executed",
                    failure);
        }
        Duration stabilizationDuration = elapsedSince(stabilizationStartedNanos);
        if (stabilization == null || !stabilization.stable()) {
            // A null result and an explicit stable()==false result are both treated as failure to
            // stabilize - never as success just because nothing explicitly threw. The pipeline must
            // never proceed to postcondition verification, and must never emit a "stable" event, on
            // an outcome the strategy itself did not report as stable.
            events.add(
                    event(
                            actionId,
                            command,
                            ActionStage.ACTION_FAILED,
                            "stabilization-not-stable",
                            targetDescription,
                            startedNanos));
            return failed(
                    context,
                    command,
                    config,
                    actionId,
                    startedNanos,
                    events,
                    resolutionDuration,
                    preconditionDuration,
                    executionDuration,
                    stabilizationDuration,
                    preconditions,
                    List.of(),
                    before,
                    target,
                    "",
                    ActionFailureType.STABILIZATION_FAILED,
                    ActionExecutionMode.REAL,
                    ActionStatus.EXECUTION_FAILED,
                    "The environment did not stabilize after the backend action already executed",
                    null);
        }
        events.add(
                event(
                        actionId,
                        command,
                        ActionStage.STABILIZATION_COMPLETED,
                        "stable",
                        targetDescription,
                        startedNanos));

        long verificationStartedNanos = clock.nanoTime();
        events.add(
                event(
                        actionId,
                        command,
                        ActionStage.VERIFICATION_STARTED,
                        "started",
                        targetDescription,
                        startedNanos));
        List<VerificationResult> postconditions;
        try {
            postconditions =
                    verificationEngine.awaitAll(
                            context,
                            config.postconditions(),
                            budget,
                            config.options().verificationInterval());
        } catch (VerificationInterruptedException failure) {
            Thread.currentThread().interrupt();
            return failed(
                    context,
                    command,
                    config,
                    actionId,
                    startedNanos,
                    events,
                    resolutionDuration,
                    preconditionDuration,
                    executionDuration,
                    elapsedSince(verificationStartedNanos),
                    preconditions,
                    List.of(),
                    before,
                    target,
                    "",
                    ActionFailureType.INTERRUPTED,
                    ActionExecutionMode.REAL,
                    ActionStatus.CANCELLED,
                    "Action verification was interrupted",
                    failure);
        }
        Duration verificationDuration = elapsedSince(verificationStartedNanos);
        events.add(
                event(
                        actionId,
                        command,
                        ActionStage.VERIFICATION_COMPLETED,
                        "completed",
                        targetDescription,
                        startedNanos));
        Optional<VerificationResult> mismatch =
                postconditions.stream().filter(result -> !result.success()).findFirst();
        if (mismatch.isPresent()) {
            VerificationResult failedVerification = mismatch.orElseThrow();
            return failed(
                    context,
                    command,
                    config,
                    actionId,
                    startedNanos,
                    events,
                    resolutionDuration,
                    preconditionDuration,
                    executionDuration,
                    verificationDuration,
                    preconditions,
                    postconditions,
                    before,
                    target,
                    "",
                    failedVerification.timedOut()
                            ? ActionFailureType.TIMEOUT
                            : ActionFailureType.POSTCONDITION_FAILED,
                    ActionExecutionMode.REAL,
                    failedVerification.timedOut()
                            ? ActionStatus.TIMEOUT
                            : ActionStatus.VERIFICATION_FAILED,
                    "An action postcondition was not satisfied",
                    null);
        }

        Observation after = captureAfter(context, config.options().observationCapture());
        ObservationDiff diff = before == null || after == null ? null : before.diff(after);
        Duration total = elapsedSince(startedNanos);
        events.add(
                event(
                        actionId,
                        command,
                        ActionStage.ACTION_COMPLETED,
                        "completed",
                        targetDescription,
                        startedNanos));
        return new ActionResult<>(
                actionId,
                command.type(),
                ActionExecutionMode.REAL,
                ActionStatus.SUCCESS,
                value,
                total,
                new ActionTimings(
                        total,
                        resolutionDuration,
                        preconditionDuration,
                        executionDuration,
                        stabilizationDuration,
                        verificationDuration),
                preconditions,
                postconditions,
                before,
                after,
                diff,
                events,
                Optional.empty(),
                new ActionDiagnostics(targetDescription, "", Map.of("execution", "once")));
    }

    /**
     * Runs deterministic target resolution and precondition evaluation without any backend side
     * effect and returns an immutable {@link IActionPlan}, built as a {@link DefaultActionPlan}.
     *
     * <p>This reuses the exact same {@link ActionTargetResolver}, {@link PreconditionEvaluator},
     * and classification logic as {@link #execute(IActionContext, ActionCommand,
     * ActionExecutionConfig)} so that {@code plan()}, {@code dryRun()}, and a real execution can
     * never disagree about whether a target resolves or a precondition holds. The supplied {@code
     * executor} is invoked, unchanged, by {@link IActionPlan#execute()}; it is expected to
     * revalidate from scratch rather than trust this snapshot.
     *
     * <p>The caller supplies the {@link ActionId} so it can build {@code executor} to run the real
     * pipeline under that same identifier: {@code IActionPlan.actionId()} and {@code
     * IActionPlan.execute().actionId()} must be equal.
     */
    <R> IActionPlan<R> prepare(
            IActionContext context,
            ActionCommand<R> command,
            ActionExecutionConfig config,
            ActionId actionId,
            Supplier<ActionResult<R>> executor) {
        List<VerificationType> expectedPostconditions = expectedPostconditionTypes(config);
        // A plan-time-only budget: never stored in the returned IActionPlan/DefaultActionPlan. A
        // real execution budget is started fresh, independently, when IActionPlan.execute() begins
        // the real pipeline - a plan built minutes ago must not appear pre-expired at that point.
        WaitBudget prepareBudget = WaitBudget.start(config.options().timeout(), clock);
        IElement target;
        try {
            target =
                    new ActionTargetResolver()
                            .resolve(command, config.options().resolutionRetry(), prepareBudget);
        } catch (ActionInterruptedException failure) {
            Thread.currentThread().interrupt();
            String targetDescription = describe(null);
            return new DefaultActionPlan<>(
                    actionId,
                    command.type(),
                    command.idempotency(),
                    command.sideEffect(),
                    ActionPlanStatus.BLOCKED,
                    targetDescription,
                    List.of(),
                    expectedPostconditions,
                    Optional.of(
                            new ActionFailure(
                                    ActionFailureType.INTERRUPTED,
                                    "Action target resolution was interrupted",
                                    config.sensitive() ? Optional.empty() : Optional.of(failure))),
                    new ActionDiagnostics(targetDescription, "", Map.of("plan", "blocked")),
                    List.of(),
                    executor);
        } catch (RuntimeException failure) {
            String targetDescription = describe(null);
            return new DefaultActionPlan<>(
                    actionId,
                    command.type(),
                    command.idempotency(),
                    command.sideEffect(),
                    ActionPlanStatus.BLOCKED,
                    targetDescription,
                    List.of(),
                    expectedPostconditions,
                    Optional.of(
                            new ActionFailure(
                                    classifyResolution(failure),
                                    "Action target could not be resolved",
                                    config.sensitive() ? Optional.empty() : Optional.of(failure))),
                    new ActionDiagnostics(
                            targetDescription,
                            renderLocatorDiagnostics(failure),
                            Map.of("plan", "blocked")),
                    List.of(),
                    executor);
        }
        String targetDescription = describe(target);
        List<VerificationResult> preconditions =
                new PreconditionEvaluator()
                        .evaluate(command.type(), target, context, config.preconditions());
        boolean preconditionsSatisfied =
                preconditions.stream().allMatch(VerificationResult::success);
        if (!preconditionsSatisfied) {
            return new DefaultActionPlan<>(
                    actionId,
                    command.type(),
                    command.idempotency(),
                    command.sideEffect(),
                    ActionPlanStatus.BLOCKED,
                    targetDescription,
                    preconditions,
                    expectedPostconditions,
                    Optional.of(
                            new ActionFailure(
                                    ActionFailureType.PRECONDITION_FAILED,
                                    "An action precondition was not satisfied",
                                    Optional.empty())),
                    new ActionDiagnostics(targetDescription, "", Map.of("plan", "blocked")),
                    List.of(),
                    executor);
        }
        List<io.webagent4j.action.ActionDecisionEntry> policyDecisions =
                snapshotPolicyDecisions(command, config, actionId, targetDescription);
        // Unlike the snapshot itself (informational only - execute() always re-evaluates fresh,
        // never trusting this), the plan's own status must not claim READY over a policy that has
        // already refused this action: a plan a caller inspects and decides to keep is a real,
        // caller-visible signal, and reporting READY for something the same policy would reject a
        // moment later at execute() is exactly the kind of authorization-shaped promise this
        // framework never makes. A DENY or evaluation failure seen here blocks the plan; only an
        // outright ALLOW (or no policy configured at all) is READY.
        for (io.webagent4j.action.ActionDecisionEntry entry : policyDecisions) {
            if (entry.outcome() == io.webagent4j.action.ActionDecisionOutcome.DENY) {
                return blockedByPolicy(
                        actionId,
                        command,
                        targetDescription,
                        preconditions,
                        expectedPostconditions,
                        policyDecisions,
                        ActionFailureType.POLICY_DENIED,
                        "An action policy denied this action",
                        executor);
            }
            if (entry.outcome() == io.webagent4j.action.ActionDecisionOutcome.EVALUATION_FAILED) {
                return blockedByPolicy(
                        actionId,
                        command,
                        targetDescription,
                        preconditions,
                        expectedPostconditions,
                        policyDecisions,
                        ActionFailureType.POLICY_EVALUATION_FAILED,
                        "Action policy evaluation failed",
                        executor);
            }
        }
        return new DefaultActionPlan<>(
                actionId,
                command.type(),
                command.idempotency(),
                command.sideEffect(),
                ActionPlanStatus.READY,
                targetDescription,
                preconditions,
                expectedPostconditions,
                Optional.empty(),
                new ActionDiagnostics(targetDescription, "", Map.of("plan", "ready")),
                policyDecisions,
                executor);
    }

    /**
     * Builds a {@link ActionPlanStatus#BLOCKED} plan for a policy that has already refused this
     * action (or failed to evaluate) during {@link #prepare}'s snapshot pass - shared by the DENY
     * and evaluation-failure cases so both carry the exact same shape.
     */
    @SuppressWarnings("checkstyle:ParameterNumber")
    private <R> IActionPlan<R> blockedByPolicy(
            ActionId actionId,
            ActionCommand<R> command,
            String targetDescription,
            List<VerificationResult> preconditions,
            List<VerificationType> expectedPostconditions,
            List<io.webagent4j.action.ActionDecisionEntry> policyDecisions,
            ActionFailureType failureType,
            String message,
            Supplier<ActionResult<R>> executor) {
        return new DefaultActionPlan<>(
                actionId,
                command.type(),
                command.idempotency(),
                command.sideEffect(),
                ActionPlanStatus.BLOCKED,
                targetDescription,
                preconditions,
                expectedPostconditions,
                Optional.of(new ActionFailure(failureType, message, Optional.empty())),
                new ActionDiagnostics(targetDescription, "", Map.of("plan", "blocked")),
                policyDecisions,
                executor);
    }

    /**
     * Evaluates {@code config.actionPolicy()} for one action and returns a terminal {@link
     * ActionResult} if the backend must not be invoked (denied, or evaluation itself failed), or
     * {@code null} if the caller may proceed. {@link RuntimeException}s thrown by the policy are
     * caught here - fail-closed - but never masked as an ordinary policy DENY: only a genuine
     * {@link RuntimeException} is caught, so a {@link Throwable} subclass signaling a fatal JVM
     * condition (for example {@link OutOfMemoryError} or {@link StackOverflowError}) still
     * propagates rather than being silently reinterpreted as a policy decision.
     */
    @SuppressWarnings("checkstyle:ParameterNumber")
    private <R> ActionResult<R> authorizeAction(
            IActionContext context,
            ActionCommand<R> command,
            ActionExecutionConfig config,
            ActionId actionId,
            long startedNanos,
            List<ActionEvent> events,
            Duration resolutionDuration,
            Duration preconditionDuration,
            List<VerificationResult> preconditions,
            Observation before,
            IElement target,
            String targetDescription,
            ActionPolicyMode mode) {
        IActionPolicy policy = config.actionPolicy().orElseThrow();
        ActionPolicyContext policyContext =
                new ActionPolicyContext(
                        actionId,
                        command.type(),
                        command.idempotency(),
                        command.sideEffect(),
                        mode,
                        targetDescription);
        events.add(
                event(
                        actionId,
                        command,
                        ActionStage.POLICY_EVALUATION_STARTED,
                        "started",
                        targetDescription,
                        startedNanos,
                        Map.of("policy.kind", "ACTION", "policy.phase", "PRE_EXECUTION")));
        PolicyDecision decision;
        try {
            decision = policy.evaluate(policyContext);
        } catch (RuntimeException evaluationFailure) {
            events.add(
                    policyCompletedEvent(
                            actionId,
                            command,
                            targetDescription,
                            startedNanos,
                            "ACTION",
                            "PRE_EXECUTION",
                            "EVALUATION_FAILED",
                            io.webagent4j.action.policy.ActionPolicyReasons.EVALUATION_FAILED
                                    .code()));
            return failed(
                    context,
                    command,
                    config,
                    actionId,
                    startedNanos,
                    events,
                    resolutionDuration,
                    preconditionDuration,
                    Duration.ZERO,
                    Duration.ZERO,
                    preconditions,
                    List.of(),
                    before,
                    target,
                    "",
                    ActionFailureType.POLICY_EVALUATION_FAILED,
                    ActionExecutionMode.NOT_EXECUTED,
                    ActionStatus.EXECUTION_FAILED,
                    "Action policy evaluation failed",
                    evaluationFailure);
        }
        if (decision == null) {
            events.add(
                    policyCompletedEvent(
                            actionId,
                            command,
                            targetDescription,
                            startedNanos,
                            "ACTION",
                            "PRE_EXECUTION",
                            "EVALUATION_FAILED",
                            io.webagent4j.action.policy.ActionPolicyReasons.EVALUATION_FAILED
                                    .code()));
            return failed(
                    context,
                    command,
                    config,
                    actionId,
                    startedNanos,
                    events,
                    resolutionDuration,
                    preconditionDuration,
                    Duration.ZERO,
                    Duration.ZERO,
                    preconditions,
                    List.of(),
                    before,
                    target,
                    "",
                    ActionFailureType.POLICY_EVALUATION_FAILED,
                    ActionExecutionMode.NOT_EXECUTED,
                    ActionStatus.EXECUTION_FAILED,
                    "Action policy returned no decision",
                    null);
        }
        if (decision.isDeny()) {
            events.add(
                    policyCompletedEvent(
                            actionId,
                            command,
                            targetDescription,
                            startedNanos,
                            "ACTION",
                            "PRE_EXECUTION",
                            "DENY",
                            decision.reason().code()));
            return failed(
                    context,
                    command,
                    config,
                    actionId,
                    startedNanos,
                    events,
                    resolutionDuration,
                    preconditionDuration,
                    Duration.ZERO,
                    Duration.ZERO,
                    preconditions,
                    List.of(),
                    before,
                    target,
                    "",
                    ActionFailureType.POLICY_DENIED,
                    ActionExecutionMode.NOT_EXECUTED,
                    ActionStatus.EXECUTION_FAILED,
                    "Action was denied by policy: " + decision.reason().code(),
                    null);
        }
        events.add(
                policyCompletedEvent(
                        actionId,
                        command,
                        targetDescription,
                        startedNanos,
                        "ACTION",
                        "PRE_EXECUTION",
                        "ALLOW",
                        decision.reason().code()));
        return null;
    }

    /**
     * Builds a safe {@link ActionStage#POLICY_EVALUATION_COMPLETED} event carrying exactly the four
     * structured {@code policy.*} metadata keys {@link ActionResult#decisionTrace()} parses back
     * into an {@code ActionDecisionEntry} - never anything else about the evaluated context.
     */
    @SuppressWarnings("checkstyle:ParameterNumber")
    private ActionEvent policyCompletedEvent(
            ActionId actionId,
            ActionCommand<?> command,
            String targetDescription,
            long startedNanos,
            String kind,
            String phase,
            String outcome,
            String reasonCode) {
        return event(
                actionId,
                command,
                ActionStage.POLICY_EVALUATION_COMPLETED,
                "completed",
                targetDescription,
                startedNanos,
                Map.of(
                        "policy.kind", kind,
                        "policy.phase", phase,
                        "policy.outcome", outcome,
                        "policy.reason", reasonCode));
    }

    /**
     * Evaluates {@code config.networkPolicy()} for one {@code NAVIGATE} action's destination and
     * returns a terminal {@link ActionResult} if it must be treated as unauthorized, or {@code
     * null} if the caller may proceed. The outcome shape differs by {@code phase}: at {@link
     * NetworkCheckPhase#PRE_REQUEST} a deny or evaluation failure fails closed with {@link
     * ActionExecutionMode#NOT_EXECUTED} (the backend has not been called yet); at {@link
     * NetworkCheckPhase#POST_REQUEST} every failure is reported as {@link
     * ActionFailureType#POLICY_VIOLATION} with {@link ActionExecutionMode#REAL} - the navigation
     * already happened by the time this phase runs, so it is never reported as not executed, even
     * when the policy itself failed to evaluate.
     */
    @SuppressWarnings("checkstyle:ParameterNumber")
    private <R> ActionResult<R> authorizeNetworkDestination(
            IActionContext context,
            ActionCommand<R> command,
            ActionExecutionConfig config,
            ActionId actionId,
            long startedNanos,
            List<ActionEvent> events,
            Duration resolutionDuration,
            Duration preconditionDuration,
            Duration executionDuration,
            Duration verificationDuration,
            List<VerificationResult> preconditions,
            Observation before,
            IElement target,
            String requestedUrl,
            NetworkCheckPhase phase) {
        INetworkPolicy policy = config.networkPolicy().orElseThrow();
        boolean postRequest = phase == NetworkCheckPhase.POST_REQUEST;
        ActionExecutionMode modeOnFailure =
                postRequest ? ActionExecutionMode.REAL : ActionExecutionMode.NOT_EXECUTED;
        ActionFailureType evaluationFailureType =
                postRequest
                        ? ActionFailureType.POLICY_VIOLATION
                        : ActionFailureType.POLICY_EVALUATION_FAILED;
        ActionFailureType denyFailureType =
                postRequest ? ActionFailureType.POLICY_VIOLATION : ActionFailureType.POLICY_DENIED;
        String decisionPhase = postRequest ? "POST_EXECUTION" : "PRE_EXECUTION";
        events.add(
                event(
                        actionId,
                        command,
                        ActionStage.POLICY_EVALUATION_STARTED,
                        "started",
                        "",
                        startedNanos,
                        Map.of("policy.kind", "NETWORK", "policy.phase", decisionPhase)));

        NetworkPolicyContext networkContext;
        try {
            NetworkDestination destination = NetworkDestination.of(URI.create(requestedUrl));
            networkContext =
                    new NetworkPolicyContext(
                            NetworkRequestKind.BROWSER_NAVIGATION, destination, phase);
        } catch (RuntimeException malformed) {
            events.add(
                    policyCompletedEvent(
                            actionId,
                            command,
                            "",
                            startedNanos,
                            "NETWORK",
                            decisionPhase,
                            "EVALUATION_FAILED",
                            io.webagent4j.policy.network.NetworkPolicyReasons.EVALUATION_FAILED
                                    .code()));
            return failed(
                    context,
                    command,
                    config,
                    actionId,
                    startedNanos,
                    events,
                    resolutionDuration,
                    preconditionDuration,
                    executionDuration,
                    verificationDuration,
                    preconditions,
                    List.of(),
                    before,
                    target,
                    "",
                    evaluationFailureType,
                    modeOnFailure,
                    ActionStatus.EXECUTION_FAILED,
                    "Navigation URL could not be evaluated against the network policy",
                    malformed);
        }

        PolicyDecision decision;
        try {
            decision = policy.evaluate(networkContext);
        } catch (RuntimeException evaluationFailure) {
            events.add(
                    policyCompletedEvent(
                            actionId,
                            command,
                            "",
                            startedNanos,
                            "NETWORK",
                            decisionPhase,
                            "EVALUATION_FAILED",
                            io.webagent4j.policy.network.NetworkPolicyReasons.EVALUATION_FAILED
                                    .code()));
            return failed(
                    context,
                    command,
                    config,
                    actionId,
                    startedNanos,
                    events,
                    resolutionDuration,
                    preconditionDuration,
                    executionDuration,
                    verificationDuration,
                    preconditions,
                    List.of(),
                    before,
                    target,
                    "",
                    evaluationFailureType,
                    modeOnFailure,
                    ActionStatus.EXECUTION_FAILED,
                    "Network policy evaluation failed",
                    evaluationFailure);
        }
        if (decision == null) {
            events.add(
                    policyCompletedEvent(
                            actionId,
                            command,
                            "",
                            startedNanos,
                            "NETWORK",
                            decisionPhase,
                            "EVALUATION_FAILED",
                            io.webagent4j.policy.network.NetworkPolicyReasons.EVALUATION_FAILED
                                    .code()));
            return failed(
                    context,
                    command,
                    config,
                    actionId,
                    startedNanos,
                    events,
                    resolutionDuration,
                    preconditionDuration,
                    executionDuration,
                    verificationDuration,
                    preconditions,
                    List.of(),
                    before,
                    target,
                    "",
                    evaluationFailureType,
                    modeOnFailure,
                    ActionStatus.EXECUTION_FAILED,
                    "Network policy returned no decision",
                    null);
        }
        if (decision.isDeny()) {
            events.add(
                    policyCompletedEvent(
                            actionId,
                            command,
                            "",
                            startedNanos,
                            "NETWORK",
                            decisionPhase,
                            "DENY",
                            decision.reason().code()));
            return failed(
                    context,
                    command,
                    config,
                    actionId,
                    startedNanos,
                    events,
                    resolutionDuration,
                    preconditionDuration,
                    executionDuration,
                    verificationDuration,
                    preconditions,
                    List.of(),
                    before,
                    target,
                    "",
                    denyFailureType,
                    modeOnFailure,
                    ActionStatus.EXECUTION_FAILED,
                    (postRequest
                                    ? "Final navigated URL was denied by network policy: "
                                    : "Navigation destination was denied by network policy: ")
                            + decision.reason().code(),
                    null);
        }
        events.add(
                policyCompletedEvent(
                        actionId,
                        command,
                        "",
                        startedNanos,
                        "NETWORK",
                        decisionPhase,
                        "ALLOW",
                        decision.reason().code()));
        return null;
    }

    private static List<VerificationType> expectedPostconditionTypes(ActionExecutionConfig config) {
        return config.postconditions().stream().map(IVerification::type).toList();
    }

    /**
     * Builds the non-authoritative {@link IActionPlan#policyDecisions()} snapshot: evaluates every
     * configured policy once, in {@link ActionPolicyMode#PLAN} for an action policy, purely for
     * inspection. Never throws and never blocks {@code prepare()} from returning a {@link
     * io.webagent4j.action.ActionPlanStatus#READY} plan - an evaluation failure here becomes an
     * {@link io.webagent4j.action.ActionDecisionOutcome#EVALUATION_FAILED} entry, not a propagated
     * exception, since this snapshot's only purpose is inspection via {@link
     * IActionPlan#policyDecisions()}.
     */
    private <R> List<io.webagent4j.action.ActionDecisionEntry> snapshotPolicyDecisions(
            ActionCommand<R> command,
            ActionExecutionConfig config,
            ActionId actionId,
            String targetDescription) {
        List<io.webagent4j.action.ActionDecisionEntry> entries = new ArrayList<>();
        config.actionPolicy()
                .ifPresent(
                        policy ->
                                entries.add(
                                        snapshotActionDecision(
                                                policy, command, actionId, targetDescription)));
        if (command.type() == io.webagent4j.action.ActionType.NAVIGATE) {
            config.networkPolicy()
                    .ifPresent(
                            policy ->
                                    command.navigationUrl()
                                            .ifPresent(
                                                    url ->
                                                            entries.add(
                                                                    snapshotNetworkDecision(
                                                                            policy, url))));
        }
        return entries;
    }

    private <R> io.webagent4j.action.ActionDecisionEntry snapshotActionDecision(
            IActionPolicy policy,
            ActionCommand<R> command,
            ActionId actionId,
            String targetDescription) {
        ActionPolicyContext policyContext =
                new ActionPolicyContext(
                        actionId,
                        command.type(),
                        command.idempotency(),
                        command.sideEffect(),
                        ActionPolicyMode.PLAN,
                        targetDescription);
        PolicyDecision decision;
        try {
            decision = policy.evaluate(policyContext);
        } catch (RuntimeException evaluationFailure) {
            return evaluationFailedActionEntry();
        }
        if (decision == null) {
            return evaluationFailedActionEntry();
        }
        return new io.webagent4j.action.ActionDecisionEntry(
                io.webagent4j.action.ActionDecisionKind.ACTION,
                io.webagent4j.action.ActionDecisionPhase.PRE_EXECUTION,
                decision.isDeny()
                        ? io.webagent4j.action.ActionDecisionOutcome.DENY
                        : io.webagent4j.action.ActionDecisionOutcome.ALLOW,
                decision.reason());
    }

    private static io.webagent4j.action.ActionDecisionEntry evaluationFailedActionEntry() {
        return new io.webagent4j.action.ActionDecisionEntry(
                io.webagent4j.action.ActionDecisionKind.ACTION,
                io.webagent4j.action.ActionDecisionPhase.PRE_EXECUTION,
                io.webagent4j.action.ActionDecisionOutcome.EVALUATION_FAILED,
                io.webagent4j.action.policy.ActionPolicyReasons.EVALUATION_FAILED);
    }

    private io.webagent4j.action.ActionDecisionEntry snapshotNetworkDecision(
            INetworkPolicy policy, String requestedUrl) {
        NetworkPolicyContext networkContext;
        try {
            NetworkDestination destination = NetworkDestination.of(URI.create(requestedUrl));
            networkContext =
                    new NetworkPolicyContext(
                            NetworkRequestKind.BROWSER_NAVIGATION,
                            destination,
                            NetworkCheckPhase.PRE_REQUEST);
        } catch (RuntimeException malformed) {
            return evaluationFailedNetworkEntry();
        }
        PolicyDecision decision;
        try {
            decision = policy.evaluate(networkContext);
        } catch (RuntimeException evaluationFailure) {
            return evaluationFailedNetworkEntry();
        }
        if (decision == null) {
            return evaluationFailedNetworkEntry();
        }
        return new io.webagent4j.action.ActionDecisionEntry(
                io.webagent4j.action.ActionDecisionKind.NETWORK,
                io.webagent4j.action.ActionDecisionPhase.PRE_EXECUTION,
                decision.isDeny()
                        ? io.webagent4j.action.ActionDecisionOutcome.DENY
                        : io.webagent4j.action.ActionDecisionOutcome.ALLOW,
                decision.reason());
    }

    private static io.webagent4j.action.ActionDecisionEntry evaluationFailedNetworkEntry() {
        return new io.webagent4j.action.ActionDecisionEntry(
                io.webagent4j.action.ActionDecisionKind.NETWORK,
                io.webagent4j.action.ActionDecisionPhase.PRE_EXECUTION,
                io.webagent4j.action.ActionDecisionOutcome.EVALUATION_FAILED,
                io.webagent4j.policy.network.NetworkPolicyReasons.EVALUATION_FAILED);
    }

    private static String renderLocatorDiagnostics(RuntimeException failure) {
        if (failure instanceof AmbiguousLocatorException ambiguous) {
            return ambiguous
                    .diagnostics()
                    .map(d -> new LocatorDiagnosticsRenderer().render(d, List.of()))
                    .orElse("");
        }
        if (failure instanceof LocatorNotFoundException notFound) {
            return notFound.diagnostics()
                    .map(d -> new LocatorDiagnosticsRenderer().render(d, List.of()))
                    .orElse("");
        }
        return "";
    }

    @SuppressWarnings("checkstyle:ParameterNumber")
    private <R> ActionResult<R> failed(
            IActionContext context,
            ActionCommand<R> command,
            ActionExecutionConfig config,
            ActionId actionId,
            long startedNanos,
            List<ActionEvent> events,
            Duration resolutionDuration,
            Duration preconditionDuration,
            Duration executionDuration,
            Duration verificationDuration,
            List<VerificationResult> preconditions,
            List<VerificationResult> postconditions,
            Observation before,
            IElement target,
            String locatorDiagnostics,
            ActionFailureType failureType,
            ActionExecutionMode executionMode,
            ActionStatus status,
            String message,
            RuntimeException cause) {
        String targetDescription = describe(target);
        events.add(
                event(
                        actionId,
                        command,
                        ActionStage.ACTION_FAILED,
                        "failed",
                        targetDescription,
                        startedNanos));
        Observation after = captureFailure(context, config.options().observationCapture());
        ObservationDiff diff = before == null || after == null ? null : before.diff(after);
        Duration total = elapsedSince(startedNanos);
        Optional<Throwable> safeCause =
                cause == null || config.sensitive() ? Optional.empty() : Optional.of(cause);
        return new ActionResult<>(
                actionId,
                command.type(),
                executionMode,
                status,
                null,
                total,
                new ActionTimings(
                        total,
                        resolutionDuration,
                        preconditionDuration,
                        executionDuration,
                        Duration.ZERO,
                        verificationDuration),
                preconditions,
                postconditions,
                before,
                after,
                diff,
                events,
                Optional.of(new ActionFailure(failureType, message, safeCause)),
                new ActionDiagnostics(
                        targetDescription,
                        locatorDiagnostics == null ? "" : locatorDiagnostics,
                        Map.of("execution", "not-retried")));
    }

    /**
     * Classifies a target-resolution failure using the typed {@link
     * io.webagent4j.common.ILocatorFailure} contract, never exception class names or message text.
     *
     * <p>Only a failure that carries a typed "not found" or "ambiguous" outcome — directly or
     * wrapped within a bounded cause chain — is classified as such. Any other failure, including a
     * genuine backend or runtime error such as a browser crash or a disconnected backend, is
     * classified as {@link ActionFailureType#BACKEND_FAILURE} and must never be silently reported
     * as a missing target.
     */
    private static ActionFailureType classifyResolution(RuntimeException failure) {
        if (LocatorFailureClassifier.isAmbiguous(failure)) {
            return ActionFailureType.TARGET_AMBIGUOUS;
        }
        if (LocatorFailureClassifier.isNotFound(failure)) {
            return ActionFailureType.TARGET_NOT_FOUND;
        }
        return ActionFailureType.BACKEND_FAILURE;
    }

    private static ActionFailureType classifyExecution(
            ActionCommand<?> command, RuntimeException failure) {
        if (command.type() == io.webagent4j.action.ActionType.UPLOAD) {
            return ActionFailureType.UPLOAD_FAILURE;
        }
        if (command.type() == io.webagent4j.action.ActionType.DOWNLOAD) {
            return ActionFailureType.DOWNLOAD_FAILURE;
        }
        return failure instanceof UnsupportedOperationException
                ? ActionFailureType.ACTION_NOT_SUPPORTED_BY_TARGET
                : ActionFailureType.BACKEND_FAILURE;
    }

    private static Observation captureBefore(
            IActionContext context, ObservationCapturePolicy policy) {
        return policy == ObservationCapturePolicy.ALWAYS
                        || policy == ObservationCapturePolicy.ON_FAILURE
                ? context.observe()
                : null;
    }

    private static Observation captureAfter(
            IActionContext context, ObservationCapturePolicy policy) {
        return policy == ObservationCapturePolicy.ALWAYS ? context.observe() : null;
    }

    private static Observation captureFailure(
            IActionContext context, ObservationCapturePolicy policy) {
        return policy == ObservationCapturePolicy.ALWAYS
                        || policy == ObservationCapturePolicy.ON_FAILURE
                ? context.observe()
                : null;
    }

    private static String describe(IElement target) {
        return target == null ? "page" : target.role() + " '" + target.accessibleName() + "'";
    }

    private ActionEvent event(
            ActionId actionId,
            ActionCommand<?> command,
            ActionStage stage,
            String result,
            String target,
            long startedNanos) {
        return event(actionId, command, stage, result, target, startedNanos, Map.of());
    }

    /**
     * Same as the five-argument overload, with additional safe, structured metadata merged in -
     * used only for {@link ActionStage#POLICY_EVALUATION_COMPLETED}'s {@code policy.*} keys, the
     * data {@link ActionResult#decisionTrace()} is derived from.
     */
    private ActionEvent event(
            ActionId actionId,
            ActionCommand<?> command,
            ActionStage stage,
            String result,
            String target,
            long startedNanos,
            Map<String, String> extraMetadata) {
        Map<String, String> metadata = new java.util.LinkedHashMap<>();
        metadata.put("idempotency", command.idempotency().name());
        metadata.putAll(extraMetadata);
        return new ActionEvent(
                actionId,
                Instant.now(),
                stage,
                command.type(),
                target,
                result,
                elapsedSince(startedNanos),
                metadata);
    }

    private Duration elapsedSince(long startedNanos) {
        return Duration.ofNanos(Math.max(0L, clock.nanoTime() - startedNanos));
    }
}
