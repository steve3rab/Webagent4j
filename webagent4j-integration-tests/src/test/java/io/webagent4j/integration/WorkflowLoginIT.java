package io.webagent4j.integration;

import static io.webagent4j.verification.Verifications.textVisible;
import static io.webagent4j.verification.Verifications.urlContains;
import static org.assertj.core.api.Assertions.assertThat;

import io.webagent4j.action.Secret;
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
import org.junit.jupiter.api.Test;

/**
 * Real-Playwright integration coverage for {@code webagent4j-workflow}: every scenario here drives
 * an actual browser against {@link ActionTestApplication}'s deterministic {@code /login} -> {@code
 * /dashboard} fixture through the real action pipeline - never a fake {@code IPreparedAction}.
 */
class WorkflowLoginIT {

    private static final WorkflowVariable<IPage> PAGE =
            WorkflowVariable.publicValue("page", IPage.class);
    private static final WorkflowVariable<String> EMAIL =
            WorkflowVariable.publicValue("email", String.class);
    private static final WorkflowVariable<String> PASSWORD = WorkflowVariable.secret("password");
    private static final WorkflowVariable<Boolean> REMEMBER =
            WorkflowVariable.publicValue("remember", Boolean.class);
    private static final WorkflowVariable<Boolean> AUTO_SUBMIT =
            WorkflowVariable.publicValue("autoSubmit", Boolean.class);

    private final WorkflowEngine engine = new WorkflowEngine();

    private static IWorkflowStep typeEmailStep() {
        return WorkflowSteps.action(
                "type-email",
                vars -> {
                    IPage page = vars.require(PAGE);
                    var email = page.find().textbox().labelled("Email").single();
                    return page.action().type(email, vars.require(EMAIL));
                });
    }

    private static IWorkflowStep typePasswordStep() {
        return WorkflowSteps.action(
                "type-password",
                vars -> {
                    IPage page = vars.require(PAGE);
                    var password = page.find().textbox().labelled("Password").single();
                    return page.action().typeSecret(password, Secret.of(vars.require(PASSWORD)));
                });
    }

    private static IWorkflowStep checkRememberStep() {
        return WorkflowSteps.action(
                        "check-remember",
                        vars -> {
                            IPage page = vars.require(PAGE);
                            var remember = page.find().checkbox().named("Remember me").single();
                            return page.action().check(remember);
                        })
                .when(WorkflowConditions.isTrue(REMEMBER));
    }

    private static IWorkflowStep signInStep() {
        return WorkflowSteps.action(
                "sign-in",
                vars -> {
                    IPage page = vars.require(PAGE);
                    var signIn = page.find().button().named("Sign in").single();
                    return page.action()
                            .click(signIn)
                            .expect(urlContains("/dashboard"))
                            .expect(textVisible("Welcome"));
                });
    }

    private static IWorkflowStep guardedSignInStep() {
        return WorkflowSteps.action(
                        "sign-in",
                        vars -> {
                            IPage page = vars.require(PAGE);
                            var signIn = page.find().button().named("Sign in").single();
                            return page.action()
                                    .click(signIn)
                                    .expect(urlContains("/dashboard"))
                                    .expect(textVisible("Welcome"));
                        })
                .when(WorkflowConditions.isTrue(AUTO_SUBMIT));
    }

    private static IWorkflowStep signInWithImpossibleExpectationStep() {
        return WorkflowSteps.action(
                "sign-in",
                vars -> {
                    IPage page = vars.require(PAGE);
                    var signIn = page.find().button().named("Sign in").single();
                    return page.action()
                            .click(signIn)
                            .expect(textVisible("This text never appears on the dashboard"));
                });
    }

    private static IWorkflowStep openNotificationsStep() {
        return WorkflowSteps.action(
                "open-notifications",
                vars -> {
                    IPage page = vars.require(PAGE);
                    var notifications = page.find().button().named("Open notifications").single();
                    return page.action().click(notifications).expect(textVisible("Notifications"));
                });
    }

    @Test
    void wfIt001MultiActionSuccessfulWorkflow() throws Exception {
        try (var support = Phase4TestSupport.start();
                var page = support.open("/login")) {
            Workflow workflow =
                    Workflow.builder("login")
                            .requiredInput(PAGE)
                            .requiredInput(EMAIL)
                            .requiredInput(PASSWORD)
                            .optionalInput(REMEMBER)
                            .step(typeEmailStep())
                            .step(typePasswordStep())
                            .step(checkRememberStep())
                            .step(signInStep())
                            .step(openNotificationsStep())
                            .build();
            WorkflowInputs inputs =
                    WorkflowInputs.builder()
                            .put(PAGE, page)
                            .put(EMAIL, "user@example.test")
                            .put(PASSWORD, "WA4J_IT_LOGIN_SECRET_001")
                            .put(REMEMBER, true)
                            .build();

            WorkflowResult result = engine.execute(workflow, inputs);

            assertThat(result.completed()).isTrue();
            assertThat(result.steps()).hasSize(5);
            assertThat(result.steps())
                    .allSatisfy(
                            step ->
                                    assertThat(step.status())
                                            .isEqualTo(WorkflowStepStatus.SUCCEEDED));
            assertThat(page.url()).contains("/dashboard");
        }
    }

    @Test
    void wfIt002SecretCredentialNeverAppearsInWorkflowResultRendering() throws Exception {
        String sentinel = "WA4J_IT_LOGIN_SECRET_002_DO_NOT_LEAK";
        try (var support = Phase4TestSupport.start();
                var page = support.open("/login")) {
            Workflow workflow =
                    Workflow.builder("login")
                            .requiredInput(PAGE)
                            .requiredInput(EMAIL)
                            .requiredInput(PASSWORD)
                            .step(typeEmailStep())
                            .step(typePasswordStep())
                            .step(signInStep())
                            .build();
            WorkflowInputs inputs =
                    WorkflowInputs.builder()
                            .put(PAGE, page)
                            .put(EMAIL, "user@example.test")
                            .put(PASSWORD, sentinel)
                            .build();

            WorkflowResult result = engine.execute(workflow, inputs);

            assertThat(result.completed()).isTrue();
            assertThat(inputs.toString()).doesNotContain(sentinel);
            assertThat(result.toString()).doesNotContain(sentinel).contains("***");
            assertThat(result.steps())
                    .allSatisfy(step -> assertThat(step.toString()).doesNotContain(sentinel));
        }
    }

    @Test
    void wfIt003ConditionFalseSkipsARealAction() throws Exception {
        try (var support = Phase4TestSupport.start();
                var page = support.open("/login")) {
            Workflow workflow =
                    Workflow.builder("login")
                            .requiredInput(PAGE)
                            .requiredInput(EMAIL)
                            .requiredInput(AUTO_SUBMIT)
                            .step(typeEmailStep())
                            .step(guardedSignInStep())
                            .build();
            WorkflowInputs inputs =
                    WorkflowInputs.builder()
                            .put(PAGE, page)
                            .put(EMAIL, "user@example.test")
                            .put(AUTO_SUBMIT, false)
                            .build();

            WorkflowResult result = engine.execute(workflow, inputs);

            assertThat(result.completed()).isTrue();
            assertThat(result.steps().get(1).status()).isEqualTo(WorkflowStepStatus.SKIPPED);
            assertThat(page.url()).doesNotContain("/dashboard");
        }
    }

    @Test
    void wfIt004RealActionFailureStopsLaterAction() throws Exception {
        try (var support = Phase4TestSupport.start();
                var page = support.open("/login")) {
            Workflow workflow =
                    Workflow.builder("login")
                            .requiredInput(PAGE)
                            .requiredInput(EMAIL)
                            .step(typeEmailStep())
                            .step(signInWithImpossibleExpectationStep())
                            .step(openNotificationsStep())
                            .build();
            WorkflowInputs inputs =
                    WorkflowInputs.builder()
                            .put(PAGE, page)
                            .put(EMAIL, "user@example.test")
                            .build();

            WorkflowResult result = engine.execute(workflow, inputs);

            assertThat(result.completed()).isFalse();
            assertThat(result.failure().orElseThrow().type())
                    .isEqualTo(WorkflowFailureType.ACTION_FAILED);
            assertThat(result.failure().orElseThrow().stepId())
                    .contains(new WorkflowStepId("sign-in"));
            assertThat(result.failure().orElseThrow().actionFailureType()).isPresent();
            assertThat(result.steps().get(0).status()).isEqualTo(WorkflowStepStatus.SUCCEEDED);
            assertThat(result.steps().get(1).status()).isEqualTo(WorkflowStepStatus.FAILED);
            assertThat(result.steps().get(2).status()).isEqualTo(WorkflowStepStatus.NOT_RUN);
        }
    }

    @Test
    void wfIt005SameWorkflowDefinitionReusedAgainstResetDeterministicPageState() throws Exception {
        Workflow workflow =
                Workflow.builder("login")
                        .requiredInput(PAGE)
                        .requiredInput(EMAIL)
                        .requiredInput(PASSWORD)
                        .step(typeEmailStep())
                        .step(typePasswordStep())
                        .step(signInStep())
                        .build();

        try (var support = Phase4TestSupport.start();
                var firstPage = support.open("/login");
                var secondPage = support.open("/login")) {
            WorkflowResult firstResult =
                    engine.execute(
                            workflow,
                            WorkflowInputs.builder()
                                    .put(PAGE, firstPage)
                                    .put(EMAIL, "first@example.test")
                                    .put(PASSWORD, "WA4J_IT_LOGIN_SECRET_005_FIRST")
                                    .build());
            WorkflowResult secondResult =
                    engine.execute(
                            workflow,
                            WorkflowInputs.builder()
                                    .put(PAGE, secondPage)
                                    .put(EMAIL, "second@example.test")
                                    .put(PASSWORD, "WA4J_IT_LOGIN_SECRET_005_SECOND")
                                    .build());

            assertThat(firstResult.completed()).isTrue();
            assertThat(secondResult.completed()).isTrue();
            assertThat(firstPage.url()).contains("/dashboard");
            assertThat(secondPage.url()).contains("/dashboard");
        }
    }
}
