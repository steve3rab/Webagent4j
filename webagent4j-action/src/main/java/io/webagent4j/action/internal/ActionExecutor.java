package io.webagent4j.action.internal;

import io.webagent4j.action.ActionDiagnostics;
import io.webagent4j.action.ActionEvent;
import io.webagent4j.action.ActionFailure;
import io.webagent4j.action.ActionFailureType;
import io.webagent4j.action.ActionId;
import io.webagent4j.action.ActionResult;
import io.webagent4j.action.ActionStage;
import io.webagent4j.action.ActionStatus;
import io.webagent4j.action.ActionTimings;
import io.webagent4j.action.IActionContext;
import io.webagent4j.action.ObservationCapturePolicy;
import io.webagent4j.dom.IElement;
import io.webagent4j.locator.AmbiguousLocatorException;
import io.webagent4j.locator.LocatorDiagnosticsRenderer;
import io.webagent4j.locator.LocatorNotFoundException;
import io.webagent4j.observation.Observation;
import io.webagent4j.observation.ObservationDiff;
import io.webagent4j.verification.VerificationEngine;
import io.webagent4j.verification.VerificationInterruptedException;
import io.webagent4j.verification.VerificationResult;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Executes the ordered resolve, validate, execute-once, stabilize, observe, verify pipeline. */
final class ActionExecutor {

    <R> ActionResult<R> execute(
            IActionContext context, ActionCommand<R> command, ActionExecutionConfig config) {
        ActionId actionId = ActionId.create();
        Instant started = Instant.now();
        List<ActionEvent> events = new ArrayList<>();
        events.add(event(actionId, command, ActionStage.ACTION_STARTED, "started", "", started));
        IElement target;
        Instant resolutionStarted = Instant.now();
        events.add(
                event(
                        actionId,
                        command,
                        ActionStage.TARGET_RESOLUTION_STARTED,
                        "started",
                        "",
                        started));
        try {
            target =
                    new ActionTargetResolver()
                            .resolve(
                                    command,
                                    config.options().resolutionRetry(),
                                    config.options().timeout());
        } catch (RuntimeException failure) {
            String locatorDiagnostics = "";
            if (failure instanceof AmbiguousLocatorException ambiguous) {
                locatorDiagnostics =
                        ambiguous
                                .diagnostics()
                                .map(d -> new LocatorDiagnosticsRenderer().render(d, List.of()))
                                .orElse("");
            } else if (failure instanceof LocatorNotFoundException notFound) {
                locatorDiagnostics =
                        notFound.diagnostics()
                                .map(d -> new LocatorDiagnosticsRenderer().render(d, List.of()))
                                .orElse("");
            }
            return failed(
                    context,
                    command,
                    config,
                    actionId,
                    started,
                    events,
                    resolutionStarted,
                    Duration.ZERO,
                    Duration.ZERO,
                    Duration.ZERO,
                    List.of(),
                    List.of(),
                    null,
                    null,
                    locatorDiagnostics,
                    classifyResolution(failure),
                    ActionStatus.EXECUTION_FAILED,
                    "Action target could not be resolved",
                    failure);
        }
        Duration resolutionDuration = Duration.between(resolutionStarted, Instant.now());
        String targetDescription = describe(target);
        events.add(
                event(
                        actionId,
                        command,
                        ActionStage.TARGET_RESOLVED,
                        "resolved",
                        targetDescription,
                        started));

        Observation before = captureBefore(context, config.options().observationCapture());
        Instant preconditionStarted = Instant.now();
        events.add(
                event(
                        actionId,
                        command,
                        ActionStage.PRECONDITION_STARTED,
                        "started",
                        targetDescription,
                        started));
        List<VerificationResult> preconditions =
                new PreconditionEvaluator()
                        .evaluate(command.type(), target, context, config.preconditions());
        Duration preconditionDuration = Duration.between(preconditionStarted, Instant.now());
        if (preconditions.stream().anyMatch(result -> !result.success())) {
            events.add(
                    event(
                            actionId,
                            command,
                            ActionStage.ACTION_FAILED,
                            "precondition-failed",
                            targetDescription,
                            started));
            return failed(
                    context,
                    command,
                    config,
                    actionId,
                    started,
                    events,
                    resolutionStarted,
                    preconditionDuration,
                    Duration.ZERO,
                    Duration.ZERO,
                    preconditions,
                    List.of(),
                    before,
                    target,
                    "",
                    ActionFailureType.PRECONDITION_FAILED,
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
                        started));

        R value = null;
        Instant executionStarted = Instant.now();
        events.add(
                event(
                        actionId,
                        command,
                        ActionStage.BACKEND_ACTION_STARTED,
                        "started",
                        targetDescription,
                        started));
        Duration executionDuration;
        if (config.dryRun()) {
            // Simulate execution without invoking backend; keep diagnostics and validations.
            executionDuration = Duration.between(executionStarted, Instant.now());
            events.add(
                    event(
                            actionId,
                            command,
                            ActionStage.BACKEND_ACTION_COMPLETED,
                            "simulated",
                            targetDescription,
                            started));
        } else {
            try {
                value = command.executeBackend(context.actionBackend(), target);
            } catch (RuntimeException failure) {
                return failed(
                        context,
                        command,
                        config,
                        actionId,
                        started,
                        events,
                        resolutionStarted,
                        preconditionDuration,
                        Duration.between(executionStarted, Instant.now()),
                        Duration.ZERO,
                        preconditions,
                        List.of(),
                        before,
                        target,
                        "",
                        classifyExecution(command, failure),
                        ActionStatus.EXECUTION_FAILED,
                        "Backend action execution failed",
                        failure);
            }
            executionDuration = Duration.between(executionStarted, Instant.now());
            events.add(
                    event(
                            actionId,
                            command,
                            ActionStage.BACKEND_ACTION_COMPLETED,
                            "executed-once",
                            targetDescription,
                            started));
        }

        Instant stabilizationStarted = Instant.now();
        events.add(
                event(
                        actionId,
                        command,
                        ActionStage.STABILIZATION_STARTED,
                        "started",
                        targetDescription,
                        started));
        Duration stabilizationDuration;
        if (config.dryRun()) {
            // Do not wait for stabilization in dry-run mode — no backend changes were applied.
            stabilizationDuration = Duration.ZERO;
            events.add(
                    event(
                            actionId,
                            command,
                            ActionStage.STABILIZATION_COMPLETED,
                            "stable",
                            targetDescription,
                            started));
        } else {
            config.stabilization().await(context, remaining(config, started));
            stabilizationDuration = Duration.between(stabilizationStarted, Instant.now());
            events.add(
                    event(
                            actionId,
                            command,
                            ActionStage.STABILIZATION_COMPLETED,
                            "stable",
                            targetDescription,
                            started));
        }

        Instant verificationStarted = Instant.now();
        events.add(
                event(
                        actionId,
                        command,
                        ActionStage.VERIFICATION_STARTED,
                        "started",
                        targetDescription,
                        started));
        List<VerificationResult> postconditions;
        try {
            postconditions =
                    new VerificationEngine()
                            .awaitAll(
                                    context,
                                    config.postconditions(),
                                    remaining(config, started),
                                    config.options().verificationInterval());
        } catch (VerificationInterruptedException failure) {
            Thread.currentThread().interrupt();
            return failed(
                    context,
                    command,
                    config,
                    actionId,
                    started,
                    events,
                    resolutionStarted,
                    preconditionDuration,
                    executionDuration,
                    Duration.between(verificationStarted, Instant.now()),
                    preconditions,
                    List.of(),
                    before,
                    target,
                    "",
                    ActionFailureType.INTERRUPTED,
                    ActionStatus.CANCELLED,
                    "Action verification was interrupted",
                    failure);
        }
        Duration verificationDuration = Duration.between(verificationStarted, Instant.now());
        events.add(
                event(
                        actionId,
                        command,
                        ActionStage.VERIFICATION_COMPLETED,
                        "completed",
                        targetDescription,
                        started));
        Optional<VerificationResult> mismatch =
                postconditions.stream().filter(result -> !result.success()).findFirst();
        if (mismatch.isPresent()) {
            VerificationResult failedVerification = mismatch.orElseThrow();
            return failed(
                    context,
                    command,
                    config,
                    actionId,
                    started,
                    events,
                    resolutionStarted,
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
                    failedVerification.timedOut()
                            ? ActionStatus.TIMEOUT
                            : ActionStatus.VERIFICATION_FAILED,
                    "An action postcondition was not satisfied",
                    null);
        }

        Observation after = captureAfter(context, config.options().observationCapture());
        ObservationDiff diff = before == null || after == null ? null : before.diff(after);
        Duration total = Duration.between(started, Instant.now());
        events.add(
                event(
                        actionId,
                        command,
                        ActionStage.ACTION_COMPLETED,
                        "completed",
                        targetDescription,
                        started));
        return new ActionResult<>(
                actionId,
                command.type(),
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
                new ActionDiagnostics(
                        targetDescription,
                        "",
                        Map.of("execution", config.dryRun() ? "dry-run" : "once")));
    }

    @SuppressWarnings("checkstyle:ParameterNumber")
    private static <R> ActionResult<R> failed(
            IActionContext context,
            ActionCommand<R> command,
            ActionExecutionConfig config,
            ActionId actionId,
            Instant started,
            List<ActionEvent> events,
            Instant resolutionStarted,
            Duration preconditionDuration,
            Duration executionDuration,
            Duration verificationDuration,
            List<VerificationResult> preconditions,
            List<VerificationResult> postconditions,
            Observation before,
            IElement target,
            String locatorDiagnostics,
            ActionFailureType failureType,
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
                        started));
        Observation after = captureFailure(context, config.options().observationCapture());
        ObservationDiff diff = before == null || after == null ? null : before.diff(after);
        Duration total = Duration.between(started, Instant.now());
        Duration resolution = Duration.between(resolutionStarted, Instant.now());
        Optional<Throwable> safeCause =
                cause == null || config.sensitive() ? Optional.empty() : Optional.of(cause);
        return new ActionResult<>(
                actionId,
                command.type(),
                status,
                null,
                total,
                new ActionTimings(
                        total,
                        resolution,
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
                        Map.of("execution", config.dryRun() ? "dry-run" : "not-retried")));
    }

    private static ActionFailureType classifyResolution(RuntimeException failure) {
        String name = failure.getClass().getSimpleName();
        return name.contains("Ambiguous")
                ? ActionFailureType.TARGET_AMBIGUOUS
                : ActionFailureType.TARGET_NOT_FOUND;
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

    private static Duration remaining(ActionExecutionConfig config, Instant started) {
        Duration elapsed = Duration.between(started, Instant.now());
        Duration remaining = config.options().timeout().minus(elapsed);
        return remaining.isNegative() || remaining.isZero() ? Duration.ofNanos(1) : remaining;
    }

    private static String describe(IElement target) {
        return target == null ? "page" : target.role() + " '" + target.accessibleName() + "'";
    }

    private static ActionEvent event(
            ActionId actionId,
            ActionCommand<?> command,
            ActionStage stage,
            String result,
            String target,
            Instant started) {
        return new ActionEvent(
                actionId,
                Instant.now(),
                stage,
                command.type(),
                target,
                result,
                Duration.between(started, Instant.now()),
                Map.of("idempotency", command.idempotency().name()));
    }
}
