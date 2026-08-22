package io.webagent4j.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import io.webagent4j.action.ActionExecutionMode;
import io.webagent4j.action.ActionFailureType;
import io.webagent4j.action.ActionResult;
import io.webagent4j.action.ActionStatus;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ActionWorkflowStepTest {

    private static final WorkflowVariable<String> OUTPUT =
            WorkflowVariable.publicValue("output", String.class);
    private static final WorkflowVariable<String> SECRET_OUTPUT =
            WorkflowVariable.secret("secretOutput");
    private static final String SECRET_SENTINEL = "WA4J_SUPER_SECRET_982734";

    private static final IWorkflowVariables NO_VARIABLES =
            new IWorkflowVariables() {
                @Override
                public <T> T require(WorkflowVariable<T> variable) {
                    throw new WorkflowVariableMissingException(variable);
                }

                @Override
                public <T> Optional<T> find(WorkflowVariable<T> variable) {
                    return Optional.empty();
                }

                @Override
                public boolean exists(WorkflowVariable<?> variable) {
                    return false;
                }
            };

    @Test
    void successWithoutOutput() {
        AtomicInteger executions = new AtomicInteger();
        ActionWorkflowStep<String> step =
                new ActionWorkflowStep<>(
                        new WorkflowStepId("s"),
                        variables ->
                                new FakePreparedAction<>(
                                        ActionResults.success("value"), executions));

        StepRunOutcome outcome = step.run(NO_VARIABLES);

        assertThat(outcome.success()).isTrue();
        assertThat(executions).hasValue(1);
    }

    @Test
    void successWithOutputPublication() {
        AtomicInteger executions = new AtomicInteger();
        ActionWorkflowStep<String> step =
                new ActionWorkflowStep<>(
                        new WorkflowStepId("s"),
                        variables ->
                                new FakePreparedAction<>(
                                        ActionResults.success("value"), executions),
                        OUTPUT);

        StepRunOutcome outcome = step.run(NO_VARIABLES);

        assertThat(outcome.success()).isTrue();
        assertThat(outcome.value()).isEqualTo("value");
    }

    @Test
    void actionFailureMapsToActionFailed() {
        AtomicInteger executions = new AtomicInteger();
        ActionWorkflowStep<String> step =
                new ActionWorkflowStep<>(
                        new WorkflowStepId("s"),
                        variables ->
                                new FakePreparedAction<>(
                                        ActionResults.<String>failure(
                                                ActionFailureType.TARGET_NOT_FOUND, "boom"),
                                        executions));

        StepRunOutcome outcome = step.run(NO_VARIABLES);

        assertThat(outcome.success()).isFalse();
        assertThat(outcome.failureType()).isEqualTo(WorkflowFailureType.ACTION_FAILED);
        assertThat(outcome.actionFailureType()).contains(ActionFailureType.TARGET_NOT_FOUND);
        assertThat(outcome.safeMessage()).contains("boom");
        assertThat(executions).hasValue(1);
    }

    @Test
    void notExecutedFailureStillMapsToActionFailedAndSummaryReflectsMode() {
        AtomicInteger executions = new AtomicInteger();
        ActionWorkflowStep<String> step =
                new ActionWorkflowStep<>(
                        new WorkflowStepId("s"),
                        variables ->
                                new FakePreparedAction<>(
                                        ActionResults.<String>notExecutedFailure(
                                                ActionFailureType.TARGET_AMBIGUOUS, "ambiguous"),
                                        executions));

        StepRunOutcome outcome = step.run(NO_VARIABLES);

        assertThat(outcome.failureType()).isEqualTo(WorkflowFailureType.ACTION_FAILED);
        assertThat(outcome.actionSummary()).isPresent();
        assertThat(outcome.actionSummary().get().executionMode())
                .isEqualTo(ActionExecutionMode.NOT_EXECUTED);
    }

    @Test
    void preBackendInterruptionProjectsCancelledNotExecutedOutcome() {
        AtomicInteger executions = new AtomicInteger();
        Workflow workflow =
                Workflow.builder("interrupted-action")
                        .step(
                                WorkflowSteps.action(
                                        "s",
                                        variables ->
                                                new FakePreparedAction<>(
                                                        ActionResults.<String>interrupted(
                                                                ActionExecutionMode.NOT_EXECUTED),
                                                        executions)))
                        .build();

        WorkflowResult result = new WorkflowEngine().execute(workflow, WorkflowInputs.empty());
        WorkflowStepResult step = result.steps().get(0);

        assertThat(result.status()).isEqualTo(WorkflowStatus.FAILED);
        assertThat(step.status()).isEqualTo(WorkflowStepStatus.FAILED);
        assertThat(step.failure().orElseThrow().type())
                .isEqualTo(WorkflowFailureType.ACTION_FAILED);
        assertThat(step.failure().orElseThrow().actionFailureType())
                .contains(ActionFailureType.INTERRUPTED);
        assertThat(step.actionSummary()).isPresent();
        assertThat(step.actionSummary().orElseThrow().status()).isEqualTo(ActionStatus.CANCELLED);
        assertThat(step.actionSummary().orElseThrow().executionMode())
                .isEqualTo(ActionExecutionMode.NOT_EXECUTED);
        assertThat(executions).hasValue(1);
    }

    @Test
    void factoryExceptionMapsToActionFactoryFailed() {
        ActionWorkflowStep<String> step =
                new ActionWorkflowStep<>(
                        new WorkflowStepId("s"),
                        variables -> {
                            throw new IllegalStateException("boom");
                        });

        StepRunOutcome outcome = step.run(NO_VARIABLES);

        assertThat(outcome.success()).isFalse();
        assertThat(outcome.failureType()).isEqualTo(WorkflowFailureType.ACTION_FACTORY_FAILED);
        assertThat(outcome.underlyingTypeName()).contains(IllegalStateException.class.getName());
    }

    @Test
    void missingVariableFromFactoryMapsToMissingVariable() {
        WorkflowVariable<String> required = WorkflowVariable.publicValue("required", String.class);
        ActionWorkflowStep<String> step =
                new ActionWorkflowStep<>(
                        new WorkflowStepId("s"),
                        variables -> {
                            variables.require(required);
                            throw new IllegalStateException("unreachable");
                        });

        StepRunOutcome outcome = step.run(NO_VARIABLES);

        assertThat(outcome.failureType()).isEqualTo(WorkflowFailureType.MISSING_VARIABLE);
    }

    @Test
    void nullFactoryReturnMapsToActionFactoryFailed() {
        ActionWorkflowStep<String> step =
                new ActionWorkflowStep<>(new WorkflowStepId("s"), variables -> null);

        StepRunOutcome outcome = step.run(NO_VARIABLES);

        assertThat(outcome.failureType()).isEqualTo(WorkflowFailureType.ACTION_FACTORY_FAILED);
        assertThat(outcome.safeMessage()).contains("returned null");
    }

    @Test
    void nullActionValueWithDeclaredOutputMapsToNullOutput() {
        AtomicInteger executions = new AtomicInteger();
        ActionWorkflowStep<String> step =
                new ActionWorkflowStep<>(
                        new WorkflowStepId("s"),
                        variables ->
                                new FakePreparedAction<>(ActionResults.success(null), executions),
                        OUTPUT);

        StepRunOutcome outcome = step.run(NO_VARIABLES);

        assertThat(outcome.failureType()).isEqualTo(WorkflowFailureType.NULL_OUTPUT);
    }

    @Test
    @SuppressWarnings("unchecked")
    void outputTypeMismatchMapsToOutputTypeMismatch() {
        AtomicInteger executions = new AtomicInteger();
        WorkflowVariable<Integer> intOutput =
                WorkflowVariable.publicValue("intOutput", Integer.class);
        ActionResult<Integer> mismatched =
                (ActionResult<Integer>) (ActionResult<?>) ActionResults.success("not-an-int");
        ActionWorkflowStep<Integer> step =
                new ActionWorkflowStep<>(
                        new WorkflowStepId("s"),
                        variables -> new FakePreparedAction<>(mismatched, executions),
                        intOutput);

        StepRunOutcome outcome = step.run(NO_VARIABLES);

        assertThat(outcome.failureType()).isEqualTo(WorkflowFailureType.OUTPUT_TYPE_MISMATCH);
    }

    @Test
    void secretOutputPublishesRawValueThroughOutcome() {
        AtomicInteger executions = new AtomicInteger();
        ActionWorkflowStep<String> step =
                new ActionWorkflowStep<>(
                        new WorkflowStepId("s"),
                        variables ->
                                new FakePreparedAction<>(
                                        ActionResults.success(SECRET_SENTINEL), executions),
                        SECRET_OUTPUT);

        StepRunOutcome outcome = step.run(NO_VARIABLES);

        assertThat(outcome.success()).isTrue();
        assertThat(outcome.value()).isEqualTo(SECRET_SENTINEL);
    }
}
