package io.webagent4j.workflow.internal;

import io.webagent4j.action.ActionFailureType;
import io.webagent4j.workflow.WorkflowActionSummary;
import io.webagent4j.workflow.WorkflowFailureType;
import java.util.Objects;
import java.util.Optional;

/**
 * Internal, implementation-detail result of running one {@link IExecutableWorkflowStep} - not
 * public API. Carries the produced value on success, or structured failure data (no raw exception
 * object) on failure, which {@code WorkflowEngine} projects into a {@code WorkflowStepResult}.
 *
 * <p>{@link #safeMessage()} is <b>not yet redacted</b> at this layer: a factory-thrown exception's
 * message could itself contain a secret value, so every message reaching this type is passed
 * through {@code WorkflowEngine}'s single {@code SecretRedactor} exactly once, at the point a
 * {@code WorkflowStepResult}/{@code WorkflowFailure} is actually built - never here, and never
 * twice.
 */
public final class StepRunOutcome {

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
    public static StepRunOutcome success(Object value, WorkflowActionSummary actionSummary) {
        return new StepRunOutcome(true, value, null, null, null, null, actionSummary);
    }

    /** A failed run with a safe, redacted diagnostic and no thrown-exception detail. */
    public static StepRunOutcome failure(WorkflowFailureType type, String safeMessage) {
        return failure(type, safeMessage, null, null, null);
    }

    /** A failed run naming the thrown exception's class, never its message. */
    public static StepRunOutcome failure(
            WorkflowFailureType type, String safeMessage, String underlyingTypeName) {
        return failure(type, safeMessage, underlyingTypeName, null, null);
    }

    /** A failed action run, carrying the safe action failure category and summary. */
    public static StepRunOutcome failure(
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

    public boolean success() {
        return success;
    }

    public Object value() {
        return value;
    }

    public WorkflowFailureType failureType() {
        return failureType;
    }

    public String safeMessage() {
        return safeMessage;
    }

    public Optional<String> underlyingTypeName() {
        return Optional.ofNullable(underlyingTypeName);
    }

    public Optional<ActionFailureType> actionFailureType() {
        return Optional.ofNullable(actionFailureType);
    }

    public Optional<WorkflowActionSummary> actionSummary() {
        return Optional.ofNullable(actionSummary);
    }
}
