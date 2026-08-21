package io.webagent4j.workflow;

import java.util.Objects;

/**
 * Stable, human-readable identifier for one {@link Workflow} definition.
 *
 * <p>Unlike {@code ActionId}, a workflow's identity is never randomly generated: a caller chooses
 * an explicit, deterministic name (for example {@code "login"} or {@code "checkout"}) so the same
 * logical workflow always carries the same identity across runs, logs, and diagnostics.
 *
 * @param value the non-blank identifier text
 */
public record WorkflowId(String value) {

    /** Validates that {@code value} is non-null and non-blank. */
    public WorkflowId {
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
