package io.webagent4j.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.RecordComponent;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class WorkflowSecretSafetyTest {

    private static final String SECRET_SENTINEL = "WA4J_SUPER_SECRET_982734";
    private static final WorkflowVariable<String> PASSWORD = WorkflowVariable.secret("password");
    private static final WorkflowVariable<String> SECRET_OUTPUT =
            WorkflowVariable.secret("secretOutput");

    private final WorkflowEngine engine = new WorkflowEngine();

    @Test
    void secretSuccessfulOutputNeverAppearsInAnyIncidentalRenderingButExplicitRetrievalWorks() {
        Workflow workflow =
                Workflow.builder("wf")
                        .requiredInput(PASSWORD)
                        .step(
                                WorkflowSteps.action(
                                        "s1",
                                        variables ->
                                                new FakePreparedAction<>(
                                                        ActionResults.success(
                                                                variables.require(PASSWORD)),
                                                        new AtomicInteger()),
                                        SECRET_OUTPUT))
                        .build();
        WorkflowInputs inputs = WorkflowInputs.builder().put(PASSWORD, SECRET_SENTINEL).build();

        WorkflowResult result = engine.execute(workflow, inputs);

        assertThat(result.completed()).isTrue();
        assertThat(inputs.toString()).doesNotContain(SECRET_SENTINEL);
        assertThat(result.toString()).doesNotContain(SECRET_SENTINEL).contains("***");
        assertThat(result.steps().get(0).toString()).doesNotContain(SECRET_SENTINEL);

        assertThat(result.output(SECRET_OUTPUT)).contains(SECRET_SENTINEL);
    }

    @Test
    void exceptionMessageContainingSecretIsRedactedEverywhere() {
        Workflow workflow =
                Workflow.builder("wf")
                        .requiredInput(PASSWORD)
                        .step(
                                WorkflowSteps.action(
                                        "s1",
                                        variables -> {
                                            throw new RuntimeException(
                                                    "bad credential "
                                                            + variables.require(PASSWORD));
                                        }))
                        .build();
        WorkflowInputs inputs = WorkflowInputs.builder().put(PASSWORD, SECRET_SENTINEL).build();

        WorkflowResult result = engine.execute(workflow, inputs);

        assertThat(result.completed()).isFalse();
        WorkflowFailure failure = result.failure().orElseThrow();
        assertThat(failure.safeMessage()).doesNotContain(SECRET_SENTINEL).contains("***");
        assertThat(failure.toString()).doesNotContain(SECRET_SENTINEL);
        assertThat(result.toString()).doesNotContain(SECRET_SENTINEL);
        assertThat(result.steps().get(0).toString()).doesNotContain(SECRET_SENTINEL);
    }

    @Test
    void overlappingSecretsAreFullyRedactedLongestFirst() {
        WorkflowVariable<String> token = WorkflowVariable.secret("token");
        Workflow workflow =
                Workflow.builder("wf")
                        .requiredInput(PASSWORD)
                        .requiredInput(token)
                        .step(
                                WorkflowSteps.action(
                                        "s1",
                                        variables -> {
                                            throw new RuntimeException(
                                                    "unexpected value " + variables.require(token));
                                        }))
                        .build();
        WorkflowInputs inputs =
                WorkflowInputs.builder().put(PASSWORD, "abc").put(token, "abcdef").build();

        WorkflowResult result = engine.execute(workflow, inputs);

        String safeMessage = result.failure().orElseThrow().safeMessage();
        assertThat(safeMessage).doesNotContain("abcdef").doesNotContain("***def").contains("***");
    }

    @Test
    void secretsDoNotLeakBetweenExecutionsOfTheSameWorkflow() {
        Workflow workflow =
                Workflow.builder("wf")
                        .requiredInput(PASSWORD)
                        .step(
                                WorkflowSteps.action(
                                        "s1",
                                        variables -> {
                                            throw new RuntimeException("run failed for reasons");
                                        }))
                        .build();

        engine.execute(workflow, WorkflowInputs.builder().put(PASSWORD, "run1secret").build());

        Workflow leakProbe =
                Workflow.builder("wf")
                        .requiredInput(PASSWORD)
                        .step(
                                WorkflowSteps.action(
                                        "s1",
                                        variables -> {
                                            throw new RuntimeException(
                                                    "unrelated text containing run1secret literally");
                                        }))
                        .build();
        WorkflowResult secondRun =
                engine.execute(
                        leakProbe, WorkflowInputs.builder().put(PASSWORD, "run2secret").build());

        assertThat(secondRun.failure().orElseThrow().safeMessage()).contains("run1secret");
    }

    @Test
    void secretConditionValueNeverAppearsInConditionDescriptionAtRuntime() {
        Workflow workflow =
                Workflow.builder("wf")
                        .requiredInput(PASSWORD)
                        .step(
                                WorkflowSteps.assign(
                                                "s1",
                                                WorkflowVariable.publicValue(
                                                        "marker", String.class),
                                                "reached")
                                        .when(WorkflowConditions.equals(PASSWORD, SECRET_SENTINEL)))
                        .build();
        WorkflowInputs inputs = WorkflowInputs.builder().put(PASSWORD, SECRET_SENTINEL).build();

        WorkflowResult result = engine.execute(workflow, inputs);

        assertThat(result.completed()).isTrue();
        String conditionDescription = result.steps().get(0).condition().orElseThrow().description();
        assertThat(conditionDescription).doesNotContain(SECRET_SENTINEL).contains("***");
    }

    @Test
    void workflowFailureNeverExposesAnArbitraryRawThrowable() {
        for (RecordComponent component : WorkflowFailure.class.getRecordComponents()) {
            assertThat(Throwable.class.isAssignableFrom(component.getType())).isFalse();
        }
    }
}
