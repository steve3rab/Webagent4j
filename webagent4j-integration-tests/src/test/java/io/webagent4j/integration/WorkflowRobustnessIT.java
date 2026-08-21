package io.webagent4j.integration;

import static io.webagent4j.verification.Verifications.textVisible;
import static org.assertj.core.api.Assertions.assertThat;

import io.webagent4j.browser.IPage;
import io.webagent4j.workflow.IWorkflowStep;
import io.webagent4j.workflow.Workflow;
import io.webagent4j.workflow.WorkflowConditions;
import io.webagent4j.workflow.WorkflowEngine;
import io.webagent4j.workflow.WorkflowFailureType;
import io.webagent4j.workflow.WorkflowInputs;
import io.webagent4j.workflow.WorkflowResult;
import io.webagent4j.workflow.WorkflowStepId;
import io.webagent4j.workflow.WorkflowStepStatus;
import io.webagent4j.workflow.WorkflowSteps;
import io.webagent4j.workflow.WorkflowVariable;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Scale and edge-case robustness coverage for {@code webagent4j-workflow}, using only real,
 * production {@code Workflow}/{@code WorkflowEngine} API - never a test fake. Two scenarios
 * (WF-ROB-004 and WF-ROB-009) exercise the real browser action pipeline against {@link
 * ActionTestApplication}; the rest are pure engine-level scale tests that need no browser.
 */
class WorkflowRobustnessIT {

    private final WorkflowEngine engine = new WorkflowEngine();

    @Test
    void wfRob001FiftySequentialCheapStepsPreserveExactOrder() {
        Workflow.Builder builder = Workflow.builder("wf-rob-001");
        List<WorkflowVariable<Integer>> outputs = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            WorkflowVariable<Integer> output =
                    WorkflowVariable.publicValue("var-" + i, Integer.class);
            outputs.add(output);
            builder.step(WorkflowSteps.assign("step-" + i, output, i));
        }
        Workflow workflow = builder.build();

        WorkflowResult result = engine.execute(workflow, WorkflowInputs.empty());

        assertThat(result.completed()).isTrue();
        assertThat(result.steps()).hasSize(50);
        for (int i = 0; i < 50; i++) {
            assertThat(result.steps().get(i).stepId()).isEqualTo(new WorkflowStepId("step-" + i));
            assertThat(result.steps().get(i).status()).isEqualTo(WorkflowStepStatus.SUCCEEDED);
            assertThat(result.output(outputs.get(i))).contains(i);
        }
    }

    @Test
    void wfRob002ManyVariablesRemainTypeCorrect() {
        Workflow.Builder builder = Workflow.builder("wf-rob-002");
        List<WorkflowVariable<String>> strings = new ArrayList<>();
        List<WorkflowVariable<Integer>> ints = new ArrayList<>();
        List<WorkflowVariable<Boolean>> booleans = new ArrayList<>();
        for (int i = 0; i < 15; i++) {
            WorkflowVariable<String> s = WorkflowVariable.publicValue("s-" + i, String.class);
            WorkflowVariable<Integer> n = WorkflowVariable.publicValue("n-" + i, Integer.class);
            WorkflowVariable<Boolean> b = WorkflowVariable.publicValue("b-" + i, Boolean.class);
            strings.add(s);
            ints.add(n);
            booleans.add(b);
            builder.step(WorkflowSteps.assign("s-step-" + i, s, "value-" + i));
            builder.step(WorkflowSteps.assign("n-step-" + i, n, i));
            builder.step(WorkflowSteps.assign("b-step-" + i, b, i % 2 == 0));
        }
        Workflow workflow = builder.build();

        WorkflowResult result = engine.execute(workflow, WorkflowInputs.empty());

        assertThat(result.completed()).isTrue();
        for (int i = 0; i < 15; i++) {
            assertThat(result.output(strings.get(i))).contains("value-" + i);
            assertThat(result.output(ints.get(i))).contains(i);
            assertThat(result.output(booleans.get(i))).contains(i % 2 == 0);
        }
    }

    @Test
    void wfRob003EarlyFailureMarksAllRemainingNotRun() {
        WorkflowVariable<Boolean> missingOptional =
                WorkflowVariable.publicValue("missingOptional", Boolean.class);
        Workflow.Builder builder = Workflow.builder("wf-rob-003").optionalInput(missingOptional);
        for (int i = 0; i < 15; i++) {
            builder.step(
                    WorkflowSteps.assign(
                            "before-" + i,
                            WorkflowVariable.publicValue("v-" + i, Integer.class),
                            i));
        }
        builder.step(
                WorkflowSteps.assign(
                                "failing", WorkflowVariable.publicValue("never", Integer.class), -1)
                        .when(WorkflowConditions.isTrue(missingOptional)));
        for (int i = 0; i < 15; i++) {
            builder.step(
                    WorkflowSteps.assign(
                            "after-" + i,
                            WorkflowVariable.publicValue("w-" + i, Integer.class),
                            i));
        }
        Workflow workflow = builder.build();

        WorkflowResult result = engine.execute(workflow, WorkflowInputs.empty());

        assertThat(result.completed()).isFalse();
        assertThat(result.failure().orElseThrow().type())
                .isEqualTo(WorkflowFailureType.CONDITION_EVALUATION_FAILED);
        assertThat(result.steps()).hasSize(31);
        for (int i = 0; i < 15; i++) {
            assertThat(result.steps().get(i).status()).isEqualTo(WorkflowStepStatus.SUCCEEDED);
        }
        assertThat(result.steps().get(15).status()).isEqualTo(WorkflowStepStatus.FAILED);
        for (int i = 16; i < 31; i++) {
            assertThat(result.steps().get(i).status()).isEqualTo(WorkflowStepStatus.NOT_RUN);
        }
    }

    @Test
    void wfRob004ManySkippedRealActionStepsInvokeZeroFactories() throws Exception {
        WorkflowVariable<Boolean> autoSubmit =
                WorkflowVariable.publicValue("autoSubmit", Boolean.class);
        WorkflowVariable<IPage> pageVariable = WorkflowVariable.publicValue("page", IPage.class);
        Workflow.Builder builder =
                Workflow.builder("wf-rob-004")
                        .requiredInput(pageVariable)
                        .requiredInput(autoSubmit);
        for (int i = 0; i < 20; i++) {
            String buttonName = "NonexistentButton" + i;
            builder.step(
                    WorkflowSteps.action(
                                    "click-" + i,
                                    vars -> {
                                        IPage page = vars.require(pageVariable);
                                        var button =
                                                page.find().button().named(buttonName).single();
                                        return page.action().click(button);
                                    })
                            .when(WorkflowConditions.isTrue(autoSubmit)));
        }
        Workflow workflow = builder.build();

        try (var support = Phase4TestSupport.start();
                var page = support.open("/login")) {
            WorkflowInputs inputs =
                    WorkflowInputs.builder().put(pageVariable, page).put(autoSubmit, false).build();

            WorkflowResult result = engine.execute(workflow, inputs);

            assertThat(result.completed()).isTrue();
            assertThat(result.steps())
                    .allSatisfy(
                            step ->
                                    assertThat(step.status())
                                            .isEqualTo(WorkflowStepStatus.SKIPPED));
        }
    }

    @Test
    void wfRob005SecretSentinelAbsentFromEveryResultRendererAtScale() {
        String sentinel = "WA4J_ROB_SECRET_005";
        WorkflowVariable<String> password = WorkflowVariable.secret("password");
        Workflow.Builder builder = Workflow.builder("wf-rob-005").requiredInput(password);
        for (int i = 0; i < 20; i++) {
            builder.step(
                    WorkflowSteps.assign(
                            "before-" + i,
                            WorkflowVariable.publicValue("v-" + i, Integer.class),
                            i));
        }
        builder.step(
                WorkflowSteps.action(
                        "leaky",
                        vars -> {
                            throw new RuntimeException("credential was " + vars.require(password));
                        }));
        Workflow workflow = builder.build();
        WorkflowInputs inputs = WorkflowInputs.builder().put(password, sentinel).build();

        WorkflowResult result = engine.execute(workflow, inputs);

        assertThat(result.completed()).isFalse();
        assertThat(inputs.toString()).doesNotContain(sentinel);
        assertThat(result.toString()).doesNotContain(sentinel).contains("***");
        assertThat(result.steps())
                .allSatisfy(step -> assertThat(step.toString()).doesNotContain(sentinel));
    }

    @Test
    void wfRob006OverlappingSecretsFullyRedactedAtScale() {
        WorkflowVariable<String> password = WorkflowVariable.secret("password");
        WorkflowVariable<String> token = WorkflowVariable.secret("token");
        Workflow.Builder builder =
                Workflow.builder("wf-rob-006").requiredInput(password).requiredInput(token);
        for (int i = 0; i < 20; i++) {
            builder.step(
                    WorkflowSteps.assign(
                            "before-" + i,
                            WorkflowVariable.publicValue("v-" + i, Integer.class),
                            i));
        }
        builder.step(
                WorkflowSteps.action(
                        "leaky",
                        vars -> {
                            throw new RuntimeException("unexpected " + vars.require(token));
                        }));
        Workflow workflow = builder.build();
        WorkflowInputs inputs =
                WorkflowInputs.builder().put(password, "sec").put(token, "secretvalue").build();

        WorkflowResult result = engine.execute(workflow, inputs);

        String safeMessage = result.failure().orElseThrow().safeMessage();
        assertThat(safeMessage).doesNotContain("secretvalue").contains("***");
    }

    @Test
    void wfRob007WorkflowReusedRepeatedlyWithDifferentInputs() {
        WorkflowVariable<Boolean> flag = WorkflowVariable.publicValue("flag", Boolean.class);
        WorkflowVariable<String> output = WorkflowVariable.publicValue("output", String.class);
        Workflow workflow =
                Workflow.builder("wf-rob-007")
                        .requiredInput(flag)
                        .step(
                                WorkflowSteps.assign("s1", output, "reached")
                                        .when(WorkflowConditions.isTrue(flag)))
                        .build();

        for (int i = 0; i < 10; i++) {
            boolean flagValue = i % 2 == 0;
            WorkflowResult result =
                    engine.execute(workflow, WorkflowInputs.builder().put(flag, flagValue).build());

            assertThat(result.completed()).isTrue();
            if (flagValue) {
                assertThat(result.output(output)).contains("reached");
                assertThat(result.steps().get(0).status()).isEqualTo(WorkflowStepStatus.SUCCEEDED);
            } else {
                assertThat(result.output(output)).isEmpty();
                assertThat(result.steps().get(0).status()).isEqualTo(WorkflowStepStatus.SKIPPED);
            }
        }
    }

    @Test
    void wfRob008FactoryThrowsWithSecretInExceptionMessageAmongRealSteps() {
        String sentinel = "WA4J_ROB_SECRET_008";
        WorkflowVariable<String> token = WorkflowVariable.secret("token");
        Workflow workflow =
                Workflow.builder("wf-rob-008")
                        .requiredInput(token)
                        .step(
                                WorkflowSteps.assign(
                                        "before",
                                        WorkflowVariable.publicValue("v", Integer.class),
                                        1))
                        .step(
                                WorkflowSteps.action(
                                        "leaky",
                                        vars -> {
                                            throw new IllegalStateException(
                                                    "bad token " + vars.require(token));
                                        }))
                        .build();
        WorkflowInputs inputs = WorkflowInputs.builder().put(token, sentinel).build();

        WorkflowResult result = engine.execute(workflow, inputs);

        assertThat(result.failure().orElseThrow().safeMessage())
                .doesNotContain(sentinel)
                .contains("***");
        assertThat(result.failure().orElseThrow().underlyingTypeName())
                .contains(IllegalStateException.class.getName());
    }

    @Test
    void wfRob009RealExecutedFailureIsClassifiedCleanlyAndNeverRetried() throws Exception {
        WorkflowVariable<IPage> pageVariable = WorkflowVariable.publicValue("page", IPage.class);
        Workflow workflow =
                Workflow.builder("wf-rob-009")
                        .requiredInput(pageVariable)
                        .step(
                                WorkflowSteps.action(
                                        "sign-in",
                                        vars -> {
                                            IPage page = vars.require(pageVariable);
                                            var signIn =
                                                    page.find().button().named("Sign in").single();
                                            return page.action()
                                                    .click(signIn)
                                                    .expect(
                                                            textVisible(
                                                                    "This text never appears on the"
                                                                            + " dashboard"));
                                        }))
                        .step(
                                WorkflowSteps.action(
                                        "open-notifications",
                                        vars -> {
                                            IPage page = vars.require(pageVariable);
                                            var notifications =
                                                    page.find()
                                                            .button()
                                                            .named("Open notifications")
                                                            .single();
                                            return page.action()
                                                    .click(notifications)
                                                    .expect(textVisible("Notifications"));
                                        }))
                        .build();

        try (var support = Phase4TestSupport.start();
                var page = support.open("/login")) {
            WorkflowResult result =
                    engine.execute(
                            workflow, WorkflowInputs.builder().put(pageVariable, page).build());

            assertThat(result.completed()).isFalse();
            assertThat(result.steps().get(0).status()).isEqualTo(WorkflowStepStatus.FAILED);
            assertThat(result.failure().orElseThrow().type())
                    .isEqualTo(WorkflowFailureType.ACTION_FAILED);
            assertThat(result.steps().get(1).status()).isEqualTo(WorkflowStepStatus.NOT_RUN);
        }
    }

    @Test
    void wfRob010ConditionalMissingOutputChainFailsDeterministically() {
        WorkflowVariable<Boolean> flag = WorkflowVariable.publicValue("flag", Boolean.class);
        WorkflowVariable<String> produced = WorkflowVariable.publicValue("produced", String.class);
        List<IWorkflowStep> steps = new ArrayList<>();
        steps.add(
                WorkflowSteps.assign("producer", produced, "value")
                        .when(WorkflowConditions.isTrue(flag)));
        steps.add(
                WorkflowSteps.action(
                        "consumer",
                        vars -> {
                            vars.require(produced);
                            throw new IllegalStateException("unreachable");
                        }));
        Workflow.Builder builder = Workflow.builder("wf-rob-010").requiredInput(flag);
        steps.forEach(builder::step);
        Workflow workflow = builder.build();

        for (int i = 0; i < 5; i++) {
            WorkflowResult result =
                    engine.execute(workflow, WorkflowInputs.builder().put(flag, false).build());

            assertThat(result.completed()).isFalse();
            assertThat(result.failure().orElseThrow().type())
                    .isEqualTo(WorkflowFailureType.MISSING_VARIABLE);
            assertThat(result.steps().get(0).status()).isEqualTo(WorkflowStepStatus.SKIPPED);
            assertThat(result.steps().get(1).status()).isEqualTo(WorkflowStepStatus.FAILED);
        }
    }
}
