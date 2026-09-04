package io.webagent4j.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.webagent4j.action.ActionFailureType;
import io.webagent4j.action.ActionResult;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class WorkflowEngineTest {

    private static final WorkflowVariable<String> USERNAME =
            WorkflowVariable.publicValue("username", String.class);
    private static final WorkflowVariable<String> PASSWORD = WorkflowVariable.secret("password");
    private static final WorkflowVariable<String> GREETING =
            WorkflowVariable.publicValue("greeting", String.class);
    private static final WorkflowVariable<Boolean> FLAG =
            WorkflowVariable.publicValue("flag", Boolean.class);
    private static final WorkflowVariable<String> OUTPUT =
            WorkflowVariable.publicValue("output", String.class);
    private static final WorkflowVariable<String> PRODUCED =
            WorkflowVariable.publicValue("produced", String.class);

    private final WorkflowEngine engine = new WorkflowEngine();

    private static IWorkflowStep actionStep(
            String id, AtomicInteger counter, ActionOutcomeSupplier outcome) {
        return WorkflowSteps.action(
                id, variables -> new FakePreparedAction<>(outcome.get(), counter));
    }

    private static IWorkflowStep actionStep(
            String id,
            AtomicInteger counter,
            ActionOutcomeSupplier outcome,
            WorkflowVariable<String> output) {
        return WorkflowSteps.action(
                id, variables -> new FakePreparedAction<>(outcome.get(), counter), output);
    }

    @FunctionalInterface
    private interface ActionOutcomeSupplier {
        ActionResult<String> get();
    }

    @Test
    void wfUnit001SingleSuccessfulStep() {
        Workflow workflow =
                Workflow.builder("wf").step(WorkflowSteps.assign("s1", GREETING, "hi")).build();

        WorkflowResult result = engine.execute(workflow, WorkflowInputs.empty());

        assertThat(result.completed()).isTrue();
        assertThat(result.output(GREETING)).contains("hi");
        assertThat(result.steps()).hasSize(1);
        assertThat(result.steps().get(0).status()).isEqualTo(WorkflowStepStatus.SUCCEEDED);
    }

    @Test
    void wfUnit002MultipleSuccessfulStepsPreserveOrder() {
        Workflow workflow =
                Workflow.builder("wf")
                        .step(WorkflowSteps.assign("s1", GREETING, "hi"))
                        .step(WorkflowSteps.assign("s2", FLAG, true))
                        .step(WorkflowSteps.assign("s3", OUTPUT, "done"))
                        .build();

        WorkflowResult result = engine.execute(workflow, WorkflowInputs.empty());

        assertThat(result.completed()).isTrue();
        assertThat(result.steps().stream().map(r -> r.stepId().value()))
                .containsExactly("s1", "s2", "s3");
        assertThat(result.steps())
                .allSatisfy(r -> assertThat(r.status()).isEqualTo(WorkflowStepStatus.SUCCEEDED));
    }

    @Test
    void wfUnit004MissingRequiredInputFailsBeforeStepZero() {
        AtomicInteger executions = new AtomicInteger();
        Workflow workflow =
                Workflow.builder("wf")
                        .requiredInput(USERNAME)
                        .step(actionStep("s1", executions, () -> ActionResults.success("ok")))
                        .build();

        WorkflowResult result = engine.execute(workflow, WorkflowInputs.empty());

        assertThat(result.completed()).isFalse();
        assertThat(result.failure()).isPresent();
        assertThat(result.failure().get().type())
                .isEqualTo(WorkflowFailureType.MISSING_REQUIRED_INPUT);
        assertThat(result.steps())
                .allSatisfy(r -> assertThat(r.status()).isEqualTo(WorkflowStepStatus.NOT_RUN));
        assertThat(executions).hasValue(0);
    }

    @Test
    void wfUnit005InputTypeMismatchRejected() {
        Workflow workflow =
                Workflow.builder("wf")
                        .requiredInput(PASSWORD)
                        .step(WorkflowSteps.assign("s1", GREETING, "hi"))
                        .build();
        WorkflowInputs inputs =
                WorkflowInputs.builder()
                        .put(
                                WorkflowVariable.publicValue("password", String.class),
                                "not-secret-typed")
                        .build();

        WorkflowResult result = engine.execute(workflow, inputs);

        assertThat(result.completed()).isFalse();
        assertThat(result.failure().get().type())
                .isEqualTo(WorkflowFailureType.INPUT_TYPE_MISMATCH);
        assertThat(result.steps())
                .allSatisfy(r -> assertThat(r.status()).isEqualTo(WorkflowStepStatus.NOT_RUN));
    }

    @Test
    void wfUnit006VariableOutputAccessibleByLaterStep() {
        AtomicReference<String> observed = new AtomicReference<>();
        Workflow workflow =
                Workflow.builder("wf")
                        .step(WorkflowSteps.assign("producer", GREETING, "hi"))
                        .step(
                                WorkflowSteps.action(
                                        "consumer",
                                        variables -> {
                                            observed.set(variables.require(GREETING));
                                            return new FakePreparedAction<>(
                                                    ActionResults.success("hi-processed"),
                                                    new AtomicInteger());
                                        },
                                        PRODUCED))
                        .build();

        WorkflowResult result = engine.execute(workflow, WorkflowInputs.empty());

        assertThat(result.completed()).isTrue();
        assertThat(observed).hasValue("hi");
        assertThat(result.output(PRODUCED)).contains("hi-processed");
    }

    @Test
    void wfUnit009ConditionTrueExecutesStep() {
        AtomicInteger executions = new AtomicInteger();
        Workflow workflow =
                Workflow.builder("wf")
                        .step(WorkflowSteps.assign("s1", FLAG, true))
                        .step(
                                actionStep("s2", executions, () -> ActionResults.success("ok"))
                                        .when(WorkflowConditions.isTrue(FLAG)))
                        .build();

        WorkflowResult result = engine.execute(workflow, WorkflowInputs.empty());

        assertThat(result.completed()).isTrue();
        assertThat(executions).hasValue(1);
        WorkflowStepResult step2 = result.steps().get(1);
        assertThat(step2.status()).isEqualTo(WorkflowStepStatus.SUCCEEDED);
        assertThat(step2.condition()).isPresent();
        assertThat(step2.condition().get().outcome()).isTrue();
    }

    @Test
    void wfUnit010ConditionFalseSkipsStepAndFactoryNeverInvoked() {
        AtomicInteger executions = new AtomicInteger();
        Workflow workflow =
                Workflow.builder("wf")
                        .step(WorkflowSteps.assign("s1", FLAG, false))
                        .step(
                                actionStep("s2", executions, () -> ActionResults.success("ok"))
                                        .when(WorkflowConditions.isTrue(FLAG)))
                        .build();

        WorkflowResult result = engine.execute(workflow, WorkflowInputs.empty());

        assertThat(result.completed()).isTrue();
        assertThat(executions).hasValue(0);
        WorkflowStepResult step2 = result.steps().get(1);
        assertThat(step2.status()).isEqualTo(WorkflowStepStatus.SKIPPED);
        assertThat(step2.condition().get().outcome()).isFalse();
    }

    @Test
    void wfUnit011MissingConditionVariableFailsExceptExistsNotExists() {
        Workflow workflow =
                Workflow.builder("wf")
                        .optionalInput(FLAG)
                        .step(
                                WorkflowSteps.assign("s1", GREETING, "hi")
                                        .when(WorkflowConditions.isTrue(FLAG)))
                        .build();

        WorkflowResult result = engine.execute(workflow, WorkflowInputs.empty());

        assertThat(result.completed()).isFalse();
        assertThat(result.failure().get().type())
                .isEqualTo(WorkflowFailureType.CONDITION_EVALUATION_FAILED);
        assertThat(result.failure().get().stepId()).contains(new WorkflowStepId("s1"));
    }

    @Test
    void wfUnit013ExistsGuardOnMissingOptionalVariableSkipsSafely() {
        Workflow workflow =
                Workflow.builder("wf")
                        .optionalInput(FLAG)
                        .step(
                                WorkflowSteps.assign("s1", GREETING, "hi")
                                        .when(WorkflowConditions.exists(FLAG)))
                        .build();

        WorkflowResult result = engine.execute(workflow, WorkflowInputs.empty());

        assertThat(result.completed()).isTrue();
        assertThat(result.steps().get(0).status()).isEqualTo(WorkflowStepStatus.SKIPPED);
    }

    @Test
    void wfUnit012SkippedProducerUnguardedConsumerFails() {
        AtomicInteger producerCalls = new AtomicInteger();
        AtomicInteger consumerCalls = new AtomicInteger();
        Workflow workflow =
                Workflow.builder("wf")
                        .requiredInput(FLAG)
                        .step(
                                actionStep(
                                                "producer",
                                                producerCalls,
                                                () -> ActionResults.success("produced-value"),
                                                PRODUCED)
                                        .when(WorkflowConditions.isTrue(FLAG)))
                        .step(
                                WorkflowSteps.action(
                                        "consumer",
                                        variables -> {
                                            variables.require(PRODUCED);
                                            consumerCalls.incrementAndGet();
                                            return new FakePreparedAction<>(
                                                    ActionResults.success("ignored"),
                                                    new AtomicInteger());
                                        }))
                        .build();
        WorkflowInputs inputs = WorkflowInputs.builder().put(FLAG, false).build();

        WorkflowResult result = engine.execute(workflow, inputs);

        assertThat(result.completed()).isFalse();
        assertThat(result.failure().get().type()).isEqualTo(WorkflowFailureType.MISSING_VARIABLE);
        assertThat(result.failure().get().stepId()).contains(new WorkflowStepId("consumer"));
        assertThat(producerCalls).hasValue(0);
        assertThat(consumerCalls).hasValue(0);
        assertThat(result.steps().get(0).status()).isEqualTo(WorkflowStepStatus.SKIPPED);
        assertThat(result.steps().get(1).status()).isEqualTo(WorkflowStepStatus.FAILED);
    }

    @Test
    void wfUnit013bAGuardedProducersOutputCannotBeStaticallyReferencedByALaterCondition() {
        // A guarded producer's output is never definitely available, since its guard may skip it
        // at runtime - so a later step's own condition statically referencing it (even one built
        // to tolerate absence, like exists()) is rejected at build time, not merely handled safely
        // at runtime as it was before this invariant existed. See docs/workflow.md#conditions.
        Workflow.Builder builder =
                Workflow.builder("wf")
                        .requiredInput(FLAG)
                        .step(
                                actionStep(
                                                "producer",
                                                new AtomicInteger(),
                                                () -> ActionResults.success("produced-value"),
                                                PRODUCED)
                                        .when(WorkflowConditions.isTrue(FLAG)))
                        .step(
                                actionStep(
                                                "consumer",
                                                new AtomicInteger(),
                                                () -> ActionResults.success("ignored"))
                                        .when(WorkflowConditions.exists(PRODUCED)));

        assertThatThrownBy(builder::build)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("produced");
    }

    @Test
    void wfUnit013bSkippedProducerConsumerSafelyProbesAbsenceAtRuntime() {
        // The same intent the old build-time-accepted pattern above demonstrated - a consumer
        // safely reacting to a guarded producer's output possibly being absent - remains fully
        // supported at runtime through explicit IWorkflowVariables#exists(), which is never
        // statically checked (only a step's own declarative when(...) condition is).
        AtomicInteger producerCalls = new AtomicInteger();
        AtomicInteger consumerCalls = new AtomicInteger();
        Workflow workflow =
                Workflow.builder("wf")
                        .requiredInput(FLAG)
                        .step(
                                actionStep(
                                                "producer",
                                                producerCalls,
                                                () -> ActionResults.success("produced-value"),
                                                PRODUCED)
                                        .when(WorkflowConditions.isTrue(FLAG)))
                        .step(
                                WorkflowSteps.action(
                                        "consumer",
                                        variables -> {
                                            assertThat(variables.exists(PRODUCED)).isFalse();
                                            return new FakePreparedAction<>(
                                                    ActionResults.success("ignored"),
                                                    consumerCalls);
                                        }))
                        .build();
        WorkflowInputs inputs = WorkflowInputs.builder().put(FLAG, false).build();

        WorkflowResult result = engine.execute(workflow, inputs);

        assertThat(result.completed()).isTrue();
        assertThat(producerCalls).hasValue(0);
        assertThat(consumerCalls).hasValue(1);
        assertThat(result.steps().get(0).status()).isEqualTo(WorkflowStepStatus.SKIPPED);
        assertThat(result.steps().get(1).status()).isEqualTo(WorkflowStepStatus.SUCCEEDED);
    }

    @Test
    void wfUnit014And015FirstFailureStopsExecutionAndMarksRemainingNotRun() {
        AtomicInteger step1Calls = new AtomicInteger();
        AtomicInteger step2Calls = new AtomicInteger();
        AtomicInteger step3Calls = new AtomicInteger();
        Workflow workflow =
                Workflow.builder("wf")
                        .step(actionStep("s1", step1Calls, () -> ActionResults.success("ok")))
                        .step(
                                actionStep(
                                        "s2",
                                        step2Calls,
                                        () ->
                                                ActionResults.<String>failure(
                                                        ActionFailureType.BACKEND_FAILURE, "boom")))
                        .step(actionStep("s3", step3Calls, () -> ActionResults.success("ok")))
                        .build();

        WorkflowResult result = engine.execute(workflow, WorkflowInputs.empty());

        assertThat(result.completed()).isFalse();
        assertThat(step1Calls).hasValue(1);
        assertThat(step2Calls).hasValue(1);
        assertThat(step3Calls).hasValue(0);
        assertThat(result.steps().get(0).status()).isEqualTo(WorkflowStepStatus.SUCCEEDED);
        assertThat(result.steps().get(1).status()).isEqualTo(WorkflowStepStatus.FAILED);
        WorkflowStepResult notRun = result.steps().get(2);
        assertThat(notRun.status()).isEqualTo(WorkflowStepStatus.NOT_RUN);
        assertThat(notRun.condition()).isEmpty();
        assertThat(notRun.outputVariableName()).isEmpty();
        assertThat(notRun.failure()).isEmpty();
        assertThat(notRun.actionSummary()).isEmpty();
    }

    @Test
    void wfUnit019ActionFailureMappedWithCategoryAndSummary() {
        AtomicInteger executions = new AtomicInteger();
        Workflow workflow =
                Workflow.builder("wf")
                        .step(
                                actionStep(
                                        "s1",
                                        executions,
                                        () ->
                                                ActionResults.<String>failure(
                                                        ActionFailureType.TARGET_NOT_FOUND,
                                                        "boom")))
                        .build();

        WorkflowResult result = engine.execute(workflow, WorkflowInputs.empty());

        assertThat(result.failure().get().type()).isEqualTo(WorkflowFailureType.ACTION_FAILED);
        assertThat(result.failure().get().actionFailureType())
                .contains(ActionFailureType.TARGET_NOT_FOUND);
        assertThat(result.failure().get().safeMessage()).contains("boom");
        assertThat(result.steps().get(0).actionSummary()).isPresent();
        assertThat(executions).hasValue(1);
    }

    @Test
    void wfUnit021And085WorkflowDefinitionReusableAcrossExecutions() {
        Workflow workflow =
                Workflow.builder("wf")
                        .requiredInput(USERNAME)
                        .step(
                                WorkflowSteps.action(
                                        "s1",
                                        variables ->
                                                new FakePreparedAction<>(
                                                        ActionResults.success(
                                                                variables.require(USERNAME)
                                                                        + "-greeted"),
                                                        new AtomicInteger()),
                                        PRODUCED))
                        .build();

        WorkflowResult result1 =
                engine.execute(workflow, WorkflowInputs.builder().put(USERNAME, "alice").build());
        WorkflowResult result2 =
                engine.execute(workflow, WorkflowInputs.builder().put(USERNAME, "bob").build());

        assertThat(result1.output(PRODUCED)).contains("alice-greeted");
        assertThat(result2.output(PRODUCED)).contains("bob-greeted");
    }

    @Test
    void wfUnit022ExecutionVariablesIsolatedBetweenRuns() {
        Workflow workflow =
                Workflow.builder("wf")
                        .optionalInput(FLAG)
                        .step(
                                WorkflowSteps.assign("s1", GREETING, "hi")
                                        .when(WorkflowConditions.exists(FLAG)))
                        .build();

        WorkflowResult withFlag =
                engine.execute(workflow, WorkflowInputs.builder().put(FLAG, true).build());
        WorkflowResult withoutFlag = engine.execute(workflow, WorkflowInputs.empty());

        assertThat(withFlag.output(GREETING)).contains("hi");
        assertThat(withoutFlag.output(GREETING)).isEmpty();
        assertThat(withoutFlag.steps().get(0).status()).isEqualTo(WorkflowStepStatus.SKIPPED);
    }

    @Test
    void wfUnit023AllStepExecutionOccursOnCallingThread() {
        long callingThreadId = Thread.currentThread().threadId();
        AtomicLong conditionThreadId = new AtomicLong(-1);
        AtomicLong factoryThreadId = new AtomicLong(-1);
        Workflow workflow =
                Workflow.builder("wf")
                        .step(WorkflowSteps.assign("s1", FLAG, true))
                        .step(
                                WorkflowSteps.action(
                                                "s2",
                                                variables -> {
                                                    factoryThreadId.set(
                                                            Thread.currentThread().threadId());
                                                    return new FakePreparedAction<>(
                                                            ActionResults.success("ok"),
                                                            new AtomicInteger());
                                                })
                                        .when(
                                                new IWorkflowCondition() {
                                                    @Override
                                                    public boolean evaluate(
                                                            IWorkflowVariables variables) {
                                                        conditionThreadId.set(
                                                                Thread.currentThread().threadId());
                                                        return variables.require(FLAG);
                                                    }

                                                    @Override
                                                    public String describe() {
                                                        return "threadCapturingCondition";
                                                    }

                                                    @Override
                                                    public Set<WorkflowVariable<?>>
                                                            referencedVariables() {
                                                        return Set.of(FLAG);
                                                    }
                                                }))
                        .build();

        WorkflowResult result = engine.execute(workflow, WorkflowInputs.empty());

        assertThat(result.completed()).isTrue();
        assertThat(conditionThreadId).hasValue(callingThreadId);
        assertThat(factoryThreadId).hasValue(callingThreadId);
    }

    @Test
    void wfUnit024StepResultListIsImmutable() {
        Workflow workflow =
                Workflow.builder("wf").step(WorkflowSteps.assign("s1", GREETING, "hi")).build();
        WorkflowResult result = engine.execute(workflow, WorkflowInputs.empty());

        List<WorkflowStepResult> steps = result.steps();

        assertThatThrownBy(() -> steps.add(steps.get(0)))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void builderDefensivelyCopiesStepsAtBuildTime() {
        Workflow.Builder builder =
                Workflow.builder("wf").step(WorkflowSteps.assign("s1", GREETING, "hi"));
        Workflow first = builder.build();
        builder.step(WorkflowSteps.assign("s2", FLAG, true));
        Workflow second = builder.build();

        WorkflowResult firstResult = engine.execute(first, WorkflowInputs.empty());
        WorkflowResult secondResult = engine.execute(second, WorkflowInputs.empty());

        assertThat(firstResult.steps()).hasSize(1);
        assertThat(secondResult.steps()).hasSize(2);
    }
}
