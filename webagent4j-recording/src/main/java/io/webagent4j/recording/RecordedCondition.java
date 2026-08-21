package io.webagent4j.recording;

import java.util.Objects;

/**
 * Safe, recorded outcome of one step's guard condition.
 *
 * <p>{@code description} is sourced only from {@code WorkflowConditionResult.description()} - the
 * engine's own already-redacted, execution-result-safe text - never re-derived by calling {@code
 * IWorkflowCondition.describe()} directly, which would bypass the engine's termination-time secret
 * redaction. {@link WorkflowReplayVerifier} never compares this field: it is diagnostic text, not
 * part of a condition's semantic outcome.
 *
 * @param outcome whether the condition evaluated to {@code true} or {@code false}
 * @param description the condition's redacted, safe description text
 */
public record RecordedCondition(boolean outcome, String description) {

    /** Validates that {@code description} is non-null. */
    public RecordedCondition {
        Objects.requireNonNull(description, "description");
    }
}
