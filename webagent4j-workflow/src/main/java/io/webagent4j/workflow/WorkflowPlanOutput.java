package io.webagent4j.workflow;

import java.util.Objects;

/**
 * Backend-neutral, static description of one {@link WorkflowPlanNode}'s declared output - never its
 * runtime value, which does not exist until a real execution actually publishes it.
 *
 * @param name the output variable's name, matching {@link WorkflowVariable#name()}
 * @param typeName the output variable's declared runtime type, rendered as {@link
 *     Class#getSimpleName()} - never the {@link Class} object itself
 * @param secret whether the output variable is {@link WorkflowVariable#secret()} - the
 *     classification only, never a value
 */
public record WorkflowPlanOutput(String name, String typeName, boolean secret) {

    /** Validates output metadata. */
    public WorkflowPlanOutput {
        Objects.requireNonNull(name, "name");
        if (name.isBlank()) {
            throw new IllegalArgumentException("name cannot be blank");
        }
        Objects.requireNonNull(typeName, "typeName");
        if (typeName.isBlank()) {
            throw new IllegalArgumentException("typeName cannot be blank");
        }
    }
}
