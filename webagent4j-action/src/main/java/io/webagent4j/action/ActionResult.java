package io.webagent4j.action;

import io.webagent4j.observation.Observation;
import io.webagent4j.observation.ObservationDiff;
import io.webagent4j.policy.PolicyReason;
import io.webagent4j.verification.VerificationResult;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Immutable structured outcome of one action pipeline. */
public record ActionResult<T>(
        ActionId actionId,
        ActionType actionType,
        ActionExecutionMode executionMode,
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
        Objects.requireNonNull(executionMode, "executionMode");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(duration, "duration");
        Objects.requireNonNull(timings, "timings");
        preconditions = List.copyOf(Objects.requireNonNull(preconditions, "preconditions"));
        postconditions = List.copyOf(Objects.requireNonNull(postconditions, "postconditions"));
        events = List.copyOf(Objects.requireNonNull(events, "events"));
        failure = Objects.requireNonNull(failure, "failure");
        Objects.requireNonNull(diagnostics, "diagnostics");
        if (duration.isNegative()) {
            throw new IllegalArgumentException("duration cannot be negative");
        }
        if (status == ActionStatus.SUCCESS && failure.isPresent()) {
            throw new IllegalArgumentException("successful actions cannot contain a failure");
        }
        if (status != ActionStatus.SUCCESS && failure.isEmpty()) {
            throw new IllegalArgumentException("failed actions must contain a failure");
        }
        requireOutcomeShape(status, executionMode, failure.map(ActionFailure::type).orElse(null));
    }

    private static void requireOutcomeShape(
            ActionStatus status, ActionExecutionMode executionMode, ActionFailureType failureType) {
        boolean valid =
                switch (status) {
                    case SUCCESS ->
                            failureType == null
                                    && (executionMode == ActionExecutionMode.REAL
                                            || executionMode == ActionExecutionMode.DRY_RUN);
                    case PRECONDITION_FAILED ->
                            executionMode == ActionExecutionMode.NOT_EXECUTED
                                    && failureType == ActionFailureType.PRECONDITION_FAILED;
                    case EXECUTION_FAILED ->
                            switch (executionMode) {
                                case NOT_EXECUTED ->
                                        failureType == ActionFailureType.TARGET_NOT_FOUND
                                                || failureType == ActionFailureType.TARGET_AMBIGUOUS
                                                || failureType == ActionFailureType.BACKEND_FAILURE
                                                || failureType == ActionFailureType.POLICY_DENIED
                                                || failureType
                                                        == ActionFailureType
                                                                .POLICY_EVALUATION_FAILED
                                                || failureType == ActionFailureType.TARGET_CHANGED;
                                case REAL ->
                                        failureType == ActionFailureType.TARGET_NOT_INTERACTABLE
                                                || failureType
                                                        == ActionFailureType
                                                                .ACTION_NOT_SUPPORTED_BY_TARGET
                                                || failureType == ActionFailureType.BACKEND_FAILURE
                                                || failureType == ActionFailureType.UPLOAD_FAILURE
                                                || failureType == ActionFailureType.DOWNLOAD_FAILURE
                                                || failureType == ActionFailureType.POLICY_VIOLATION
                                                || failureType
                                                        == ActionFailureType.STABILIZATION_FAILED;
                                case DRY_RUN -> false;
                            };
                    case VERIFICATION_FAILED ->
                            executionMode == ActionExecutionMode.REAL
                                    && failureType == ActionFailureType.POSTCONDITION_FAILED;
                    case TIMEOUT ->
                            (executionMode == ActionExecutionMode.REAL
                                            || executionMode == ActionExecutionMode.NOT_EXECUTED)
                                    && failureType == ActionFailureType.TIMEOUT;
                    case CANCELLED ->
                            (executionMode == ActionExecutionMode.REAL
                                            || executionMode == ActionExecutionMode.NOT_EXECUTED)
                                    && failureType == ActionFailureType.INTERRUPTED;
                };
        if (!valid) {
            throw new IllegalArgumentException(
                    "action status, execution mode, and failure type are inconsistent");
        }
    }

    /**
     * Compatibility constructor retained for the original click API.
     *
     * <p>A plain success flag cannot distinguish {@link ActionExecutionMode#REAL} from {@link
     * ActionExecutionMode#DRY_RUN} or {@link ActionExecutionMode#NOT_EXECUTED}, so a failure is
     * never assumed to mean the backend was not invoked: this constructor always reports {@link
     * ActionExecutionMode#REAL}, whether {@code success} is {@code true} or {@code false}. This is
     * the fail-safe choice, since {@link #executed()} returning {@code true} signals "already
     * attempted, do not blindly retry" rather than "definitely completed"; an unattempted
     * resolution or precondition failure incorrectly marked {@code REAL} would be far less
     * dangerous than an actually-attempted failure incorrectly marked {@link
     * ActionExecutionMode#NOT_EXECUTED}, which could invite an unsafe duplicate execution. Callers
     * that know the true execution mode should use {@link #ActionResult(boolean, Object, Duration,
     * List, Optional, ActionExecutionMode)} or the canonical constructor instead.
     *
     * @deprecated cannot represent dry-run or not-executed outcomes; prefer the canonical
     *     constructor or the explicit-execution-mode overload
     */
    @Deprecated
    public ActionResult(
            boolean success,
            T value,
            Duration duration,
            List<ActionEvent> events,
            Optional<ActionFailure> failure) {
        this(success, value, duration, events, failure, ActionExecutionMode.REAL);
    }

    /**
     * Compatibility constructor retained for the original click API, with an explicit execution
     * mode supplied by a caller that knows whether the backend was actually invoked.
     */
    public ActionResult(
            boolean success,
            T value,
            Duration duration,
            List<ActionEvent> events,
            Optional<ActionFailure> failure,
            ActionExecutionMode executionMode) {
        this(
                ActionId.create(),
                ActionType.CLICK,
                executionMode,
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
        return executionMode == ActionExecutionMode.DRY_RUN;
    }

    /**
     * Returns whether the backend action was actually invoked, regardless of whether the overall
     * result succeeded.
     *
     * <p>This is {@code true} whenever the pipeline reached backend execution at least once, which
     * happens at most once per action. It remains {@code true} even when backend execution itself
     * threw, since the backend call was genuinely made and a side effect may already have happened;
     * it does not by itself certify that the side effect completed successfully — combine it with
     * {@link #success()} for that. It is {@code false} for a dry-run ({@link #dryRun()}) and for
     * any outcome decided before backend execution, such as a resolution or precondition failure.
     */
    public boolean executed() {
        return executionMode == ActionExecutionMode.REAL;
    }

    /** Returns a compact summary suitable for logs, CLI output, and diagnostics. */
    public String toCompactText() {
        return new CompactTextActionResultRenderer().render(this);
    }

    /**
     * Returns the ordered sequence of governed-execution decisions made while producing this
     * result, derived lazily from {@link #events()} on every call rather than stored - an
     * ungoverned action never pays any cost for a trace it will never contain.
     *
     * <p>Empty whenever no governed-execution policy was configured for this action, including for
     * every value produced by one of this record's compatibility constructors: those never emit a
     * {@link ActionStage#POLICY_EVALUATION_COMPLETED} event, so there is nothing to parse. A future
     * event whose {@code policy.*} metadata this method cannot parse is skipped rather than thrown,
     * so this method never crashes a caller that only wants the trace.
     */
    public ActionDecisionTrace decisionTrace() {
        List<ActionDecisionEntry> entries = new ArrayList<>();
        for (ActionEvent event : events) {
            if (event.stage() != ActionStage.POLICY_EVALUATION_COMPLETED) {
                continue;
            }
            Map<String, String> metadata = event.metadata();
            try {
                entries.add(
                        new ActionDecisionEntry(
                                ActionDecisionKind.valueOf(metadata.get("policy.kind")),
                                ActionDecisionPhase.valueOf(metadata.get("policy.phase")),
                                ActionDecisionOutcome.valueOf(metadata.get("policy.outcome")),
                                PolicyReason.of(metadata.get("policy.reason"))));
            } catch (RuntimeException malformed) {
                // Defensive only: every event this class itself ever produces parses cleanly:
                // skip rather than let one unparseable entry crash the whole trace.
            }
        }
        return new ActionDecisionTrace(entries);
    }

    /** Throws a structured exception when this result is unsuccessful. */
    public ActionResult<T> throwIfFailed() {
        if (!success()) {
            throw new ActionFailedException(this);
        }
        return this;
    }
}
