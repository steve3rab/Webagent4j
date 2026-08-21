package io.webagent4j.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class WorkflowBuilderTest {

    private static final WorkflowVariable<String> USERNAME =
            WorkflowVariable.publicValue("username", String.class);
    private static final WorkflowVariable<String> GREETING =
            WorkflowVariable.publicValue("greeting", String.class);
    private static final WorkflowVariable<Boolean> FLAG =
            WorkflowVariable.publicValue("flag", Boolean.class);

    private static IWorkflowStep assign(
            String id, WorkflowVariable<String> variable, String value) {
        return WorkflowSteps.assign(id, variable, value);
    }

    @Test
    void blankWorkflowIdRejected() {
        assertThatThrownBy(() -> Workflow.builder("  "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void emptyStepListRejected() {
        Workflow.Builder builder = Workflow.builder("wf");

        assertThatThrownBy(builder::build).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void duplicateStepIdRejected() {
        Workflow.Builder builder =
                Workflow.builder("wf")
                        .step(assign("step-1", GREETING, "hi"))
                        .step(WorkflowSteps.assign("step-1", FLAG, true));

        assertThatThrownBy(builder::build)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("step-1");
    }

    @Test
    void conflictingVariableDeclarationRejected() {
        Workflow.Builder builder =
                Workflow.builder("wf")
                        .requiredInput(USERNAME)
                        .requiredInput(WorkflowVariable.publicValue("username", Integer.class))
                        .step(assign("step-1", GREETING, "hi"));

        assertThatThrownBy(builder::build)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("username");
    }

    @Test
    void outputCollidingWithExistingInputRejected() {
        Workflow.Builder builder =
                Workflow.builder("wf")
                        .requiredInput(GREETING)
                        .step(assign("step-1", GREETING, "hi"));

        assertThatThrownBy(builder::build)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("greeting");
    }

    @Test
    void outputCollidingWithEarlierStepOutputRejected() {
        Workflow.Builder builder =
                Workflow.builder("wf")
                        .step(assign("step-1", GREETING, "hi"))
                        .step(assign("step-2", GREETING, "bye"));

        assertThatThrownBy(builder::build)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("greeting");
    }

    @Test
    void conditionReferencingFutureStepOutputRejected() {
        AtomicInteger factoryCalls = new AtomicInteger();
        IWorkflowStep laterProducer = assign("producer", GREETING, "hi");
        IWorkflowStep earlyConsumer =
                WorkflowSteps.action(
                                "consumer",
                                variables -> {
                                    factoryCalls.incrementAndGet();
                                    return new FakePreparedAction<>(
                                            ActionResults.success("ok"), new AtomicInteger());
                                })
                        .when(WorkflowConditions.exists(GREETING));
        Workflow.Builder builder = Workflow.builder("wf").step(earlyConsumer).step(laterProducer);

        assertThatThrownBy(builder::build)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("greeting");
        assertThat(factoryCalls).hasValue(0);
    }

    @Test
    void conditionReferencingUndeclaredVariableRejected() {
        Workflow.Builder builder =
                Workflow.builder("wf")
                        .step(
                                assign("step-1", GREETING, "hi")
                                        .when(WorkflowConditions.exists(FLAG)));

        assertThatThrownBy(builder::build)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("flag");
    }

    @Test
    void conditionReferencingDeclaredInputIsAccepted() {
        Workflow workflow =
                Workflow.builder("wf")
                        .requiredInput(FLAG)
                        .step(
                                assign("step-1", GREETING, "hi")
                                        .when(WorkflowConditions.isTrue(FLAG)))
                        .build();

        assertThat(workflow.id()).isEqualTo(new WorkflowId("wf"));
    }

    @Test
    void conditionReferencingEarlierStepOutputIsAccepted() {
        Workflow workflow =
                Workflow.builder("wf")
                        .step(assign("producer", GREETING, "hi"))
                        .step(
                                WorkflowSteps.assign("consumer", FLAG, true)
                                        .when(WorkflowConditions.exists(GREETING)))
                        .build();

        assertThat(workflow.id()).isEqualTo(new WorkflowId("wf"));
    }

    @Test
    void buildNeverInvokesActionFactory() {
        AtomicInteger factoryCalls = new AtomicInteger();
        Workflow.builder("wf")
                .step(
                        WorkflowSteps.action(
                                "step-1",
                                variables -> {
                                    factoryCalls.incrementAndGet();
                                    return new FakePreparedAction<>(
                                            ActionResults.success("ok"), new AtomicInteger());
                                }))
                .build();

        assertThat(factoryCalls).hasValue(0);
    }

    @Test
    void workflowToStringNeverLeaksSecretValueAndMarksSensitivity() {
        String sentinel = "WA4J_SUPER_SECRET_982734";
        Workflow workflow =
                Workflow.builder("login")
                        .requiredInput(USERNAME)
                        .requiredInput(WorkflowVariable.secret("password"))
                        .step(assign("step-1", GREETING, sentinel))
                        .build();

        String rendered = workflow.toString();

        assertThat(rendered)
                .contains("username")
                .contains("password")
                .contains("secret")
                .contains("step-1")
                .doesNotContain(sentinel);
    }

    @Test
    void assignStepRejectsSecretVariable() {
        assertThatThrownBy(
                        () ->
                                WorkflowSteps.assign(
                                        "step-1", WorkflowVariable.secret("password"), "x"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("secret");
    }
}
