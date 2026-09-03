package io.webagent4j.workflow;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Builds a {@link WorkflowExecutionPlan} from a {@link Workflow}'s own already-validated static
 * definition - a dedicated planner, kept separate from {@link WorkflowEngine}, so that planning
 * (structural analysis) and execution (running real steps) never share a code path (see {@code
 * docs/workflow.md#execution-plan}).
 *
 * <p>{@link #plan(Workflow)} never evaluates a condition, never calls {@link
 * IWorkflowActionFactory#prepare}, never resolves or verifies a backend target, and never performs
 * any side effect: it reads only static step metadata already present on the definition - each
 * step's ID, {@link WorkflowStepType}, whether it carries an optional guard, its declared output
 * variable, and, for a {@link WorkflowStepType#CONDITIONAL} step, its {@code thenSteps}/{@code
 * elseSteps} structure - never a caller-supplied callback. Recursion into a conditional's branches
 * is bounded by the same {@link Workflow#MAX_CONDITIONAL_NESTING_DEPTH} every {@code Workflow} it
 * can receive is already bounded by (enforced once, at {@link Workflow.Builder#build()}), so no
 * independent depth check or iterative rewrite is needed here to stay within a normal JVM stack -
 * the same precedent {@link WorkflowEngine}'s own recursive traversal relies on.
 */
public final class WorkflowPlanner {

    private WorkflowPlanner() {}

    /**
     * Builds a deterministic {@link WorkflowExecutionPlan} for {@code workflow}: every structurally
     * possible path through its definition, as a tree proportional in size to the number of steps
     * the definition declares - never an exponential expansion of every combination of branch
     * outcomes. Two plans built from the same {@link Workflow} are always logically equal.
     */
    public static WorkflowExecutionPlan plan(Workflow workflow) {
        Objects.requireNonNull(workflow, "workflow");
        return new WorkflowExecutionPlan(workflow.id(), planNodes(workflow.steps()));
    }

    private static List<WorkflowPlanNode> planNodes(List<IWorkflowStep> steps) {
        List<WorkflowPlanNode> nodes = new ArrayList<>(steps.size());
        for (IWorkflowStep step : steps) {
            nodes.add(planNode(step));
        }
        return List.copyOf(nodes);
    }

    private static WorkflowPlanNode planNode(IWorkflowStep step) {
        // Safe: IWorkflowStep is sealed and permits only AWorkflowStep (see its Javadoc), so every
        // instance reachable here is guaranteed to be one - same precedent WorkflowEngine relies
        // on.
        AWorkflowStep concreteStep = (AWorkflowStep) step;
        WorkflowStepType stepType = concreteStep.stepType();
        if (concreteStep instanceof ConditionalWorkflowStep conditional) {
            List<WorkflowPlanBranch> branches =
                    List.of(
                            new WorkflowPlanBranch(
                                    WorkflowBranchSelection.THEN,
                                    planNodes(conditional.thenSteps())),
                            planElseBranch(conditional.elseSteps()));
            return new WorkflowPlanNode(step.id(), stepType, false, Optional.empty(), branches);
        }
        boolean guarded = step.condition().isPresent();
        Optional<WorkflowPlanOutput> declaredOutput =
                concreteStep.outputVariable().map(WorkflowPlanner::toPlanOutput);
        return new WorkflowPlanNode(step.id(), stepType, guarded, declaredOutput, List.of());
    }

    private static WorkflowPlanBranch planElseBranch(Optional<List<IWorkflowStep>> elseSteps) {
        return elseSteps
                .map(
                        steps ->
                                new WorkflowPlanBranch(
                                        WorkflowBranchSelection.ELSE, planNodes(steps)))
                .orElseGet(() -> new WorkflowPlanBranch(WorkflowBranchSelection.NONE, List.of()));
    }

    private static WorkflowPlanOutput toPlanOutput(WorkflowVariable<?> variable) {
        return new WorkflowPlanOutput(
                variable.name(), variable.type().getSimpleName(), variable.secret());
    }
}
