package io.webagent4j.workflow;

import java.util.Objects;

/**
 * Bundles one {@link WorkflowEngine#executeWithTree(Workflow, WorkflowInputs)} call's existing flat
 * {@link WorkflowResult} together with its {@link WorkflowExecutionTree} - both built from the
 * exact same single execution pass, never a second one.
 *
 * <p>{@link WorkflowResult} is a public record whose canonical constructor is itself public API;
 * adding the tree as a new record component there would change that constructor's signature and
 * break existing callers. This type is the additive alternative: {@link #result()} is exactly what
 * {@link WorkflowEngine#execute(Workflow, WorkflowInputs)} already returns, unchanged.
 *
 * @param result the execution's existing flat result
 * @param tree the same execution's structured execution tree
 */
public record WorkflowExecution(WorkflowResult result, WorkflowExecutionTree tree) {

    /** Validates that neither component is {@code null}. */
    public WorkflowExecution {
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(tree, "tree");
    }
}
