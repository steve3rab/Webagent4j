package io.webagent4j.workflow.internal;

import io.webagent4j.action.ActionFailure;
import io.webagent4j.action.ActionFailureType;
import io.webagent4j.action.ActionResult;
import io.webagent4j.action.IPreparedAction;
import io.webagent4j.workflow.IWorkflowActionFactory;
import io.webagent4j.workflow.IWorkflowCondition;
import io.webagent4j.workflow.IWorkflowVariables;
import io.webagent4j.workflow.WorkflowActionSummary;
import io.webagent4j.workflow.WorkflowFailureType;
import io.webagent4j.workflow.WorkflowStepId;
import io.webagent4j.workflow.WorkflowStepType;
import io.webagent4j.workflow.WorkflowVariable;
import io.webagent4j.workflow.WorkflowVariableMissingException;
import java.util.Objects;
import java.util.Optional;

/**
 * Action-backed workflow step - not public API. Produced only by {@code WorkflowSteps.action}.
 *
 * <p>{@link #run} calls {@link IWorkflowActionFactory#prepare} exactly once, then {@link
 * IPreparedAction#execute()} exactly once - never {@code plan()}, never a second call to either.
 */
public final class ActionWorkflowStep<R> extends AWorkflowStep {

    private final IWorkflowActionFactory<R> factory;
    private final WorkflowVariable<R> outputVariable;

    public ActionWorkflowStep(WorkflowStepId id, IWorkflowActionFactory<R> factory) {
        this(id, factory, null, null);
    }

    public ActionWorkflowStep(
            WorkflowStepId id,
            IWorkflowActionFactory<R> factory,
            WorkflowVariable<R> outputVariable) {
        this(id, factory, outputVariable, null);
    }

    private ActionWorkflowStep(
            WorkflowStepId id,
            IWorkflowActionFactory<R> factory,
            WorkflowVariable<R> outputVariable,
            IWorkflowCondition condition) {
        super(id, condition);
        this.factory = Objects.requireNonNull(factory, "factory");
        this.outputVariable = outputVariable;
    }

    @Override
    public WorkflowStepType stepType() {
        return WorkflowStepType.ACTION;
    }

    @Override
    public Optional<WorkflowVariable<?>> outputVariable() {
        return Optional.ofNullable(outputVariable);
    }

    @Override
    protected AWorkflowStep withCondition(IWorkflowCondition condition) {
        return new ActionWorkflowStep<>(id(), factory, outputVariable, condition);
    }

    @Override
    public StepRunOutcome run(IWorkflowVariables variables) {
        IPreparedAction<R> prepared;
        try {
            prepared = factory.prepare(variables);
        } catch (WorkflowVariableMissingException e) {
            return StepRunOutcome.failure(WorkflowFailureType.MISSING_VARIABLE, e.getMessage());
        } catch (RuntimeException e) {
            return StepRunOutcome.failure(
                    WorkflowFailureType.ACTION_FACTORY_FAILED,
                    e.getMessage() == null
                            ? "action factory threw " + e.getClass().getSimpleName()
                            : e.getMessage(),
                    e.getClass().getName());
        }
        if (prepared == null) {
            return StepRunOutcome.failure(
                    WorkflowFailureType.ACTION_FACTORY_FAILED,
                    "action factory for step '" + id().value() + "' returned null");
        }

        ActionResult<R> result = prepared.execute();
        WorkflowActionSummary summary =
                new WorkflowActionSummary(
                        result.actionId(),
                        result.actionType(),
                        result.status(),
                        result.executionMode());

        if (!result.success()) {
            String message =
                    result.failure()
                            .map(ActionFailure::message)
                            .orElse("action failed with status " + result.status());
            ActionFailureType actionFailureType =
                    result.failure().map(ActionFailure::type).orElse(null);
            return StepRunOutcome.failure(
                    WorkflowFailureType.ACTION_FAILED, message, null, actionFailureType, summary);
        }

        if (outputVariable == null) {
            return StepRunOutcome.success(null, summary);
        }
        R value = result.value();
        if (value == null) {
            return StepRunOutcome.failure(
                    WorkflowFailureType.NULL_OUTPUT,
                    "step '"
                            + id().value()
                            + "' declared output '"
                            + outputVariable.name()
                            + "' but the action produced no value",
                    null,
                    null,
                    summary);
        }
        if (!outputVariable.type().isInstance(value)) {
            return StepRunOutcome.failure(
                    WorkflowFailureType.OUTPUT_TYPE_MISMATCH,
                    "step '"
                            + id().value()
                            + "' output '"
                            + outputVariable.name()
                            + "' requires "
                            + outputVariable.type().getName()
                            + ", action produced "
                            + value.getClass().getName(),
                    null,
                    null,
                    summary);
        }
        return StepRunOutcome.success(value, summary);
    }
}
