package io.webagent4j.workflow;

import java.util.Set;

/**
 * Simple, deterministic, side-effect-free declarative condition guarding a workflow step.
 *
 * <p>Built-in conditions are created through {@link WorkflowConditions}; this is not an arbitrary
 * {@code Predicate} or scripting facade - see {@code docs/workflow.md#conditions} for the full,
 * intentionally small set of supported conditions and their missing-variable semantics.
 *
 * <p>{@link #evaluate} must never mutate {@code variables} or any external state, and must return
 * the same result for the same variable values every time it is called.
 */
public interface IWorkflowCondition {

    /**
     * Evaluates this condition against the current execution variables.
     *
     * @throws WorkflowVariableMissingException if the condition requires a variable that is not
     *     present (fail-closed: only {@code exists}/{@code notExists} tolerate a missing variable)
     */
    boolean evaluate(IWorkflowVariables variables);

    /** Returns a safe, human-readable description - never a secret value. */
    String describe();

    /**
     * Returns every variable this condition reads, used by {@link Workflow.Builder#build()} to
     * statically reject a condition that references a variable that is neither a declared input nor
     * produced by an earlier step (see {@code docs/workflow.md#conditions}).
     */
    Set<WorkflowVariable<?>> referencedVariables();
}
