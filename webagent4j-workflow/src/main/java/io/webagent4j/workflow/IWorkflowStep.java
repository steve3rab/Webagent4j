package io.webagent4j.workflow;

import java.util.Optional;

/**
 * Structural definition of one ordered step within a {@link Workflow}.
 *
 * <p>Instances are created only through {@link WorkflowSteps} - there is intentionally no public
 * constructor and no exposed execution method here, since a step's execution behavior is an engine
 * implementation detail (see {@code io.webagent4j.workflow.internal}). This interface exposes only
 * the structural information {@link Workflow.Builder} needs to validate a definition: its identity
 * and its optional guard condition.
 */
public interface IWorkflowStep {

    /** Returns this step's unique identifier within its workflow. */
    WorkflowStepId id();

    /** Returns this step's guard condition, if any. */
    Optional<IWorkflowCondition> condition();

    /**
     * Returns a copy of this step guarded by {@code condition}: {@link WorkflowStepStatus#SKIPPED}
     * when it evaluates to {@code false} rather than executing.
     */
    IWorkflowStep when(IWorkflowCondition condition);
}
