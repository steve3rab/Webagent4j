package io.webagent4j.workflow;

import java.util.List;
import java.util.Objects;

/**
 * Immutable, hierarchical view of one workflow execution's actual control-flow path, built once,
 * during that same execution - see {@link WorkflowEngine#executeWithTree(Workflow, WorkflowInputs)}
 * and {@code docs/workflow.md#execution-tree}.
 *
 * <p>This is a structural companion to the existing flat {@link WorkflowResult#steps()}, not a
 * replacement for it: flattening {@link #nodes()} in execution order yields the identical sequence
 * of {@link WorkflowStepResult}s (by reference) that {@link WorkflowResult#steps()} already
 * returns. It is observational only - building or reading it never evaluates a condition, invokes
 * an action, or selects a branch a second time.
 *
 * @param workflowId the executed workflow's identifier - the same value as {@link
 *     WorkflowResult#workflowId()}
 * @param nodes the top-level execution nodes, in execution order
 */
public record WorkflowExecutionTree(WorkflowId workflowId, List<WorkflowExecutionNode> nodes) {

    /** Validates and defensively copies tree data. */
    public WorkflowExecutionTree {
        Objects.requireNonNull(workflowId, "workflowId");
        nodes = List.copyOf(Objects.requireNonNull(nodes, "nodes"));
    }
}
