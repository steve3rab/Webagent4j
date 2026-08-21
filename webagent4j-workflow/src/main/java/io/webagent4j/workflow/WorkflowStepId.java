package io.webagent4j.workflow;

import java.util.Objects;

/**
 * Stable, human-readable identifier for one step within a {@link Workflow}.
 *
 * <p>Every step in a workflow must carry a unique {@code WorkflowStepId}; {@link Workflow.Builder}
 * rejects duplicates at build time rather than auto-suffixing them, so a step ID is always exactly
 * what the caller chose (for example {@code "type-email"} or {@code "submit"}).
 *
 * @param value the non-blank identifier text
 */
public record WorkflowStepId(String value) {

    /** Validates that {@code value} is non-null and non-blank. */
    public WorkflowStepId {
        Objects.requireNonNull(value, "value");
        if (value.isBlank()) {
            throw new IllegalArgumentException("value cannot be blank");
        }
    }

    /** Returns the raw identifier text. */
    @Override
    public String toString() {
        return value;
    }
}
