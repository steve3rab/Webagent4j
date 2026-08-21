package io.webagent4j.workflow;

import java.util.Objects;
import java.util.Optional;

/**
 * A deterministic step that assigns one literal, already-validated public value to a variable - not
 * public API. Produced only by {@link WorkflowSteps#assign}, which rejects a secret variable before
 * ever constructing this step (see its Javadoc for why secret literals are not supported).
 */
final class AssignWorkflowStep<T> extends AWorkflowStep {

    private final WorkflowVariable<T> variable;
    private final T value;

    AssignWorkflowStep(WorkflowStepId id, WorkflowVariable<T> variable, T value) {
        this(id, variable, value, null);
    }

    private AssignWorkflowStep(
            WorkflowStepId id,
            WorkflowVariable<T> variable,
            T value,
            IWorkflowCondition condition) {
        super(id, condition);
        this.variable = Objects.requireNonNull(variable, "variable");
        this.value = Objects.requireNonNull(value, "value");
    }

    @Override
    WorkflowStepType stepType() {
        return WorkflowStepType.ASSIGN;
    }

    @Override
    Optional<WorkflowVariable<?>> outputVariable() {
        return Optional.of(variable);
    }

    @Override
    AWorkflowStep withCondition(IWorkflowCondition condition) {
        return new AssignWorkflowStep<>(id(), variable, value, condition);
    }

    @Override
    StepRunOutcome run(IWorkflowVariables variables) {
        return StepRunOutcome.success(value, null);
    }
}
