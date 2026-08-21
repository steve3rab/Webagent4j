package io.webagent4j.workflow;

import java.util.Set;

/**
 * Simple, deterministic, side-effect-free declarative condition guarding a workflow step.
 *
 * <p>{@link WorkflowConditions} supplies the built-in, intentionally small declarative condition
 * set (see {@code docs/workflow.md#conditions} for the full list and their missing-variable
 * semantics) - it is not an arbitrary {@code Predicate} or scripting facade. Unlike {@link
 * IWorkflowStep}, this interface is a <b>trusted Java extension point</b>: a caller may implement
 * it directly. Every method here is treated as caller-supplied code, and {@link WorkflowEngine}
 * handles {@link #evaluate} and {@link #describe} defensively at execution time - a {@link
 * RuntimeException} from either, or a {@code null} {@link #describe} result, becomes a structured
 * {@code CONDITION_EVALUATION_FAILED} failure rather than propagating - and this applies equally
 * when the condition is wrapped by a {@link WorkflowConditions} combinator ({@code not}, {@code
 * allOf}, {@code anyOf}): a combinator preserves a wrapped condition's throwing or {@code null}
 * {@link #describe} result rather than normalizing it into ordinary composed text - but a custom
 * implementation is still expected to honor the contracts documented on each method below, and to
 * redact/omit any secret value it knows about from {@link #describe}'s own text (the engine
 * additionally redacts every currently-known secret out of a stored description as defense in
 * depth, but that is not a substitute for a condition never emitting one intentionally). {@link
 * #referencedVariables} is validated separately and earlier, at {@code Workflow.Builder#build()} -
 * definition time, before any execution exists: a {@code null} result, a result containing {@code
 * null}, or a {@link RuntimeException} from that method is a definition error and throws {@link
 * IllegalArgumentException} directly, never a {@link WorkflowResult}.
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

    /**
     * Returns a safe, human-readable description - never a secret value. A description stored in a
     * {@link WorkflowConditionResult} is always redacted against every currently-known secret and
     * length-bounded by {@link WorkflowEngine} before it is stored. A caller invoking this method
     * directly, outside {@code WorkflowEngine}, receives the condition's own text as-is: for a
     * built-in condition, that is a crash-safe rendering of any public literal it compares against,
     * deliberately <em>not yet</em> bounded, since bounding it before the engine's execution-time
     * redaction could otherwise truncate a secret mid-value and leak a partial fragment.
     */
    String describe();

    /**
     * Returns every variable this condition reads, used by {@link Workflow.Builder#build()} to
     * statically reject a condition that references a variable that is neither a declared input nor
     * produced by an earlier step (see {@code docs/workflow.md#conditions}). Must never be {@code
     * null} and must never contain a {@code null} element; {@link Workflow.Builder#build()} rejects
     * a condition whose metadata violates either as a definition error.
     */
    Set<WorkflowVariable<?>> referencedVariables();
}
