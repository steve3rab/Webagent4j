package io.webagent4j.workflow;

import io.webagent4j.action.ActionFailureType;
import java.util.Objects;
import java.util.Optional;

/**
 * Internal, implementation-detail result of running one {@link AWorkflowStep} - not public API.
 * Carries the produced value on success, or structured failure data (no raw exception object) on
 * failure, which {@link WorkflowEngine} projects into a {@link WorkflowStepResult}.
 *
 * <p>{@link #safeMessage()} is <b>not yet redacted or bounded</b> at this layer: a factory-thrown
 * exception's message could itself contain a secret value or be arbitrarily long, so every message
 * reaching this type is passed through {@code WorkflowEngine}'s single redaction-then-bounding
 * helper exactly once, at the point a {@link WorkflowStepResult}/{@link WorkflowFailure} is
 * actually built - never here, and never twice.
 */
final class StepRunOutcome {

    private final boolean success;
    private final Object value;
    private final WorkflowFailureType failureType;
    private final String safeMessage;
    private final String underlyingTypeName;
    private final ActionFailureType actionFailureType;
    private final WorkflowActionSummary actionSummary;

    private StepRunOutcome(
            boolean success,
            Object value,
            WorkflowFailureType failureType,
            String safeMessage,
            String underlyingTypeName,
            ActionFailureType actionFailureType,
            WorkflowActionSummary actionSummary) {
        this.success = success;
        this.value = value;
        this.failureType = failureType;
        this.safeMessage = safeMessage;
        this.underlyingTypeName = underlyingTypeName;
        this.actionFailureType = actionFailureType;
        this.actionSummary = actionSummary;
    }

    /** A successful run producing {@code value} (may be {@code null} if no output). */
    static StepRunOutcome success(Object value, WorkflowActionSummary actionSummary) {
        return new StepRunOutcome(true, value, null, null, null, null, actionSummary);
    }

    /** A failed run with a safe, not-yet-redacted diagnostic and no thrown-exception detail. */
    static StepRunOutcome failure(WorkflowFailureType type, String safeMessage) {
        return failure(type, safeMessage, null, null, null);
    }

    /** A failed run naming the thrown exception's class, never its message. */
    static StepRunOutcome failure(
            WorkflowFailureType type, String safeMessage, String underlyingTypeName) {
        return failure(type, safeMessage, underlyingTypeName, null, null);
    }

    /** A failed action run, carrying the safe action failure category and summary. */
    static StepRunOutcome failure(
            WorkflowFailureType type,
            String safeMessage,
            String underlyingTypeName,
            ActionFailureType actionFailureType,
            WorkflowActionSummary actionSummary) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(safeMessage, "safeMessage");
        return new StepRunOutcome(
                false,
                null,
                type,
                safeMessage,
                underlyingTypeName,
                actionFailureType,
                actionSummary);
    }

    boolean success() {
        return success;
    }

    Object value() {
        return value;
    }

    WorkflowFailureType failureType() {
        return failureType;
    }

    String safeMessage() {
        return safeMessage;
    }

    Optional<String> underlyingTypeName() {
        return Optional.ofNullable(underlyingTypeName);
    }

    Optional<ActionFailureType> actionFailureType() {
        return Optional.ofNullable(actionFailureType);
    }

    Optional<WorkflowActionSummary> actionSummary() {
        return Optional.ofNullable(actionSummary);
    }
}
