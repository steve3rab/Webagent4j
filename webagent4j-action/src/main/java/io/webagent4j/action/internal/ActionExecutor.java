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
import io.webagent4j.common.LocatorFailureClassifier;
import io.webagent4j.dom.IElement;
import io.webagent4j.locator.AmbiguousLocatorException;
import io.webagent4j.locator.LocatorDiagnosticsRenderer;
import io.webagent4j.locator.LocatorNotFoundException;
import io.webagent4j.observation.Observation;
import io.webagent4j.observation.ObservationDiff;
import io.webagent4j.verification.IVerification;
import io.webagent4j.verification.VerificationEngine;
import io.webagent4j.verification.VerificationInterruptedException;
import io.webagent4j.verification.VerificationResult;
import io.webagent4j.verification.VerificationType;
import io.webagent4j.wait.IMonotonicClock;
import io.webagent4j.wait.WaitBudget;
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

    ActionExecutor() {
        this(IMonotonicClock.systemClock());
    }

    ActionExecutor(IMonotonicClock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
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
            value = command.executeBackend(context.actionBackend(), target);
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

        long stabilizationStartedNanos = clock.nanoTime();
        events.add(
                event(
                        actionId,
                        command,
                        ActionStage.STABILIZATION_STARTED,
                        "started",
                        targetDescription,
                        startedNanos));
        config.stabilization().await(context, budget.remaining());
        Duration stabilizationDuration = elapsedSince(stabilizationStartedNanos);
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
                    new VerificationEngine()
                            .awaitAll(
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
                    executor);
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
                executor);
    }

    private static List<VerificationType> expectedPostconditionTypes(ActionExecutionConfig config) {
        return config.postconditions().stream().map(IVerification::type).toList();
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
        return new ActionEvent(
                actionId,
                Instant.now(),
                stage,
                command.type(),
                target,
                result,
                elapsedSince(startedNanos),
                Map.of("idempotency", command.idempotency().name()));
    }

    private Duration elapsedSince(long startedNanos) {
        return Duration.ofNanos(Math.max(0L, clock.nanoTime() - startedNanos));
    }
}
