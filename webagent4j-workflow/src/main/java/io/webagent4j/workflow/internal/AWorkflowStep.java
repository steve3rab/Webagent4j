package io.webagent4j.workflow.internal;

import io.webagent4j.workflow.IWorkflowCondition;
import io.webagent4j.workflow.IWorkflowStep;
import io.webagent4j.workflow.WorkflowStepId;
import java.util.Objects;
import java.util.Optional;

/**
 * Shared identity/condition storage for every concrete {@link IExecutableWorkflowStep} - not public
 * API. Not a public class: reached only through {@code WorkflowSteps}' factory methods.
 */
public abstract class AWorkflowStep implements IExecutableWorkflowStep {

    private final WorkflowStepId id;
    private final IWorkflowCondition condition;

    protected AWorkflowStep(WorkflowStepId id, IWorkflowCondition condition) {
        this.id = Objects.requireNonNull(id, "id");
        this.condition = condition;
    }

    @Override
    public final WorkflowStepId id() {
        return id;
    }

    @Override
    public final Optional<IWorkflowCondition> condition() {
        return Optional.ofNullable(condition);
    }

    @Override
    public final IWorkflowStep when(IWorkflowCondition condition) {
        return withCondition(Objects.requireNonNull(condition, "condition"));
    }

    /** Returns a copy of this step with {@code condition} attached. */
    protected abstract AWorkflowStep withCondition(IWorkflowCondition condition);
}
