package io.webagent4j.action;

import io.webagent4j.observation.Observation;
import io.webagent4j.observation.ObservationDiff;
import io.webagent4j.verification.VerificationResult;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Immutable structured outcome of one action pipeline. */
public record ActionResult<T>(
        ActionId actionId,
        ActionType actionType,
        ActionStatus status,
        T value,
        Duration duration,
        ActionTimings timings,
        List<VerificationResult> preconditions,
        List<VerificationResult> postconditions,
        Observation beforeObservation,
        Observation afterObservation,
        ObservationDiff diff,
        List<ActionEvent> events,
        Optional<ActionFailure> failure,
        ActionDiagnostics diagnostics) {

    /** Validates and defensively stores action result data. */
    public ActionResult {
        Objects.requireNonNull(actionId, "actionId");
        Objects.requireNonNull(actionType, "actionType");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(duration, "duration");
        Objects.requireNonNull(timings, "timings");
        preconditions = List.copyOf(Objects.requireNonNull(preconditions, "preconditions"));
        postconditions = List.copyOf(Objects.requireNonNull(postconditions, "postconditions"));
        events = List.copyOf(Objects.requireNonNull(events, "events"));
        failure = Objects.requireNonNull(failure, "failure");
        Objects.requireNonNull(diagnostics, "diagnostics");
        if (status == ActionStatus.SUCCESS && failure.isPresent()) {
            throw new IllegalArgumentException("successful actions cannot contain a failure");
        }
        if (status != ActionStatus.SUCCESS && failure.isEmpty()) {
            throw new IllegalArgumentException("failed actions must contain a failure");
        }
    }

    /** Compatibility constructor retained for the original click API. */
    public ActionResult(
            boolean success,
            T value,
            Duration duration,
            List<ActionEvent> events,
            Optional<ActionFailure> failure) {
        this(
                ActionId.create(),
                ActionType.CLICK,
                success ? ActionStatus.SUCCESS : ActionStatus.EXECUTION_FAILED,
                value,
                duration,
                ActionTimings.empty(duration),
                List.of(),
                List.of(),
                null,
                null,
                null,
                events,
                failure,
                ActionDiagnostics.empty());
    }

    /** Returns whether the complete action and verification pipeline succeeded. */
    public boolean success() {
        return status == ActionStatus.SUCCESS;
    }

    /**
     * Returns whether this result represents a simulated dry-run rather than a backend execution.
     */
    public boolean dryRun() {
        return "dry-run".equalsIgnoreCase(diagnostics.details().getOrDefault("execution", ""));
    }

    /**
     * Returns whether the backend action was actually executed, even when the result is successful.
     */
    public boolean executed() {
        return !dryRun();
    }

    /** Returns a compact summary suitable for logs, CLI output, and diagnostics. */
    public String toCompactText() {
        return new CompactTextActionResultRenderer().render(this);
    }

    /** Throws a structured exception when this result is unsuccessful. */
    public ActionResult<T> throwIfFailed() {
        if (!success()) {
            throw new ActionFailedException(this);
        }
        return this;
    }
}
