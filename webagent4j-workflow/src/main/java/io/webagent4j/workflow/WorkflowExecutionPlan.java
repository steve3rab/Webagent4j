package io.webagent4j.workflow;

import java.util.List;
import java.util.Objects;

/**
 * Immutable, deterministic, backend-neutral description of what a {@link Workflow} is structurally
 * capable of executing - built entirely from its definition, without ever running it (see {@link
 * WorkflowPlanner#plan(Workflow)} and {@code docs/workflow.md#execution-plan}).
 *
 * <p>This is a structural sibling to {@link WorkflowExecutionTree}, not the same concept: the plan
 * describes every path a workflow's definition could take, before any execution exists; the tree
 * describes the single path one specific execution actually took. Building a plan never evaluates a
 * condition, never invokes an {@link IWorkflowActionFactory}, never resolves or verifies a backend
 * target, and never performs any side effect - {@link WorkflowPlanner#plan(Workflow)} reads only
 * the workflow's own already-validated static structure.
 *
 * @param workflowId the planned workflow's identifier - the same value as {@link Workflow#id()}
 * @param nodes the top-level plan nodes, in definition order
 */
public record WorkflowExecutionPlan(WorkflowId workflowId, List<WorkflowPlanNode> nodes) {

    /** Validates and defensively copies plan data. */
    public WorkflowExecutionPlan {
        Objects.requireNonNull(workflowId, "workflowId");
        nodes = List.copyOf(Objects.requireNonNull(nodes, "nodes"));
    }
}
