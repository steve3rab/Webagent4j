package io.webagent4j.workflow;

import java.util.Objects;

/**
 * One structurally declared output's static metadata, as surfaced by {@link
 * WorkflowIntrospector#inspect(Workflow)} - name, declared runtime type, whether it is secret, and
 * whether every structurally possible execution path is guaranteed to publish it before whatever
 * follows its producer. Never a value: see {@link WorkflowIntrospectionReport}'s class-level
 * secret-safety note.
 *
 * <p>{@link #definitelyAvailable()} uses the exact same guard-aware definite-assignment rule {@code
 * Workflow.Builder#build()}/{@code validate()} already apply (see {@code
 * docs/workflow.md#branching}): a guarded producer's output is never definite; a conditional's
 * output is definite only when both branches unconditionally guarantee it, identically; a loop
 * body's output is never definite, since the loop may run zero iterations; a {@code PARALLEL}
 * branch's output is definite only when both the {@code PARALLEL} step itself and that specific
 * producing step are unguarded.
 *
 * @param name the declared output variable's name
 * @param typeName the declared output variable's runtime type's simple name (see {@link
 *     Class#getSimpleName()})
 * @param secret whether this output is {@link WorkflowVariable#secret()}
 * @param definitelyAvailable whether every structurally possible execution path guarantees this
 *     output is published before whatever structurally follows its producer
 */
public record WorkflowIntrospectionOutput(
        String name, String typeName, boolean secret, boolean definitelyAvailable) {

    /** Validates output metadata. */
    public WorkflowIntrospectionOutput {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(typeName, "typeName");
    }
}
