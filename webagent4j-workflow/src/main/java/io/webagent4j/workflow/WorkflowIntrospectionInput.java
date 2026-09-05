package io.webagent4j.workflow;

import java.util.Objects;

/**
 * One declared input's static metadata, as surfaced by {@link
 * WorkflowIntrospector#inspect(Workflow)} - name, declared runtime type, whether it is required,
 * and whether it is secret. Never a value: see {@link WorkflowIntrospectionReport}'s class-level
 * secret-safety note.
 *
 * @param name the declared variable's name
 * @param typeName the declared variable's runtime type's simple name (see {@link
 *     Class#getSimpleName()})
 * @param required whether this input was declared with {@code Workflow.Builder#requiredInput}
 *     rather than {@code Workflow.Builder#optionalInput}
 * @param secret whether this input is {@link WorkflowVariable#secret()}
 */
public record WorkflowIntrospectionInput(
        String name, String typeName, boolean required, boolean secret) {

    /** Validates input metadata. */
    public WorkflowIntrospectionInput {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(typeName, "typeName");
    }
}
