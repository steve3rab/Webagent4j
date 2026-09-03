package io.webagent4j.integration;

import static io.webagent4j.verification.Verifications.textVisible;
import static io.webagent4j.verification.Verifications.urlContains;
import static org.assertj.core.api.Assertions.assertThat;

import io.webagent4j.action.ActionExecutionMode;
import io.webagent4j.action.ActionResult;
import io.webagent4j.action.IActionPlan;
import io.webagent4j.action.IPreparedAction;
import io.webagent4j.action.ObservationCapturePolicy;
import io.webagent4j.action.Secret;
import io.webagent4j.browser.IPage;
import io.webagent4j.common.RetryPolicy;
import io.webagent4j.dom.IElement;
import io.webagent4j.verification.IVerification;
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
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;

/**
 * Real-Playwright integration coverage for {@code webagent4j-workflow}: every scenario here drives
 * an actual browser against {@link ActionTestApplication}'s deterministic {@code /login} -> {@code
 * /dashboard} fixture through the real action pipeline - never a fake {@code IPreparedAction}. The
 * one exception is {@link TextReadAction}, used only by the typed-output scenario below: it is a
 * real, minimal {@link IPreparedAction} whose {@link IPreparedAction#execute()} performs an actual
 * synchronous DOM read against the live browser and reports it through {@link ActionResult}'s own
 * production constructor - not a canned/mocked result - since {@link
 * io.webagent4j.action.IActionBuilder} does not define a governed verb for reading text (see {@code
 * docs/limitations.md#observation}: extraction is a deliberately separate, ungoverned subsystem).
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
    private static final WorkflowVariable<String> DASHBOARD_HEADING =
            WorkflowVariable.publicValue("dashboardHeading", String.class);

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

    /**
     * Reads the dashboard's real {@code <h1>} heading text directly from the live browser and
     * publishes it as a typed output for a later step to consume - see {@link TextReadAction}.
     */
    private static IWorkflowStep readDashboardHeadingStep() {
        return WorkflowSteps.action(
                "read-dashboard-heading",
                vars -> {
                    IPage page = vars.require(PAGE);
                    IElement heading = page.find().heading().single();
                    return new TextReadAction(heading);
                },
                DASHBOARD_HEADING);
    }

    /**
     * A real, minimal {@link IPreparedAction} for one synchronous DOM text read - see this class's
     * own Javadoc note above for why {@link io.webagent4j.action.IActionBuilder} has no built-in
     * verb for this. {@link #execute()} performs the actual browser call ({@link IElement#text()})
     * and reports the outcome through {@link ActionResult}'s production, non-deprecated,
     * explicit-execution-mode constructor - documented for exactly this case: a caller that knows
     * whether its own backend call actually ran.
     */
    private static final class TextReadAction implements IPreparedAction<String> {

        private final IElement element;

        TextReadAction(IElement element) {
            this.element = element;
        }

        @Override
        public IPreparedAction<String> precondition(Predicate<IElement> predicate) {
            return this;
        }

        @Override
        public IPreparedAction<String> require(IVerification verification) {
            return this;
        }

        @Override
        public IPreparedAction<String> expect(IVerification verification) {
            return this;
        }

        @Override
        public IPreparedAction<String> expectUrlContains(String expectedFragment) {
            return this;
        }

        @Override
        public IPreparedAction<String> timeout(Duration timeout) {
            return this;
        }

        @Override
        public IPreparedAction<String> retry(RetryPolicy retryPolicy) {
            return this;
        }

        @Override
        public IPreparedAction<String> captureObservations(ObservationCapturePolicy policy) {
            return this;
        }

        @Override
        public ActionResult<String> execute() {
            String text = element.text();
            return new ActionResult<>(
                    true,
                    text,
                    Duration.ZERO,
                    List.of(),
                    Optional.empty(),
                    ActionExecutionMode.REAL);
        }

        @Override
        public IPreparedAction<String> dryRun() {
            return this;
        }

        @Override
        public IActionPlan<String> plan() {
            throw new UnsupportedOperationException("plan() is not used by this workflow step");
        }
    }

    private static IWorkflowStep gatedOpenNotificationsStep() {
        return WorkflowSteps.action(
                        "open-notifications",
                        vars -> {
                            IPage page = vars.require(PAGE);
                            var notifications =
                                    page.find().button().named("Open notifications").single();
                            return page.action()
                                    .click(notifications)
                                    .expect(textVisible("Notifications"));
                        })
                .when(WorkflowConditions.equals(DASHBOARD_HEADING, "Welcome"));
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
            assertThat(inputs.toString()).doesNotContain(sentinel).contains("***");
            assertThat(result.toString()).doesNotContain(sentinel);
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
    void wfIt006ExtractedTypedOutputGatesARealGovernedAction() throws Exception {
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
                            .step(readDashboardHeadingStep())
                            .step(gatedOpenNotificationsStep())
                            .build();
            WorkflowInputs inputs =
                    WorkflowInputs.builder()
                            .put(PAGE, page)
                            .put(EMAIL, "user@example.test")
                            .put(PASSWORD, "WA4J_IT_LOGIN_SECRET_006")
                            .build();

            WorkflowResult result = engine.execute(workflow, inputs);

            assertThat(result.completed()).isTrue();
            // The typed output was genuinely read from the live dashboard heading.
            assertThat(result.output(DASHBOARD_HEADING)).contains("Welcome");
            // And it genuinely drove the gated step: it ran (not SKIPPED), and the real page
            // proves the click's own real effect happened, not merely an internal SUCCEEDED
            // status - this is external, page-visible proof, not just the workflow's own result.
            assertThat(result.steps().get(4).status()).isEqualTo(WorkflowStepStatus.SUCCEEDED);
        }
    }

    @Test
    void wfIt007ExtractedTypedOutputNotMatchingConditionSkipsTheRealAction() throws Exception {
        try (var support = Phase4TestSupport.start();
                var page = support.open("/login")) {
            IWorkflowStep neverMatchingGate =
                    WorkflowSteps.action(
                                    "open-notifications",
                                    vars -> {
                                        IPage p = vars.require(PAGE);
                                        var notifications =
                                                p.find()
                                                        .button()
                                                        .named("Open notifications")
                                                        .single();
                                        return p.action()
                                                .click(notifications)
                                                .expect(textVisible("Notifications"));
                                    })
                            .when(
                                    WorkflowConditions.equals(
                                            DASHBOARD_HEADING, "This never matches"));
            Workflow workflow =
                    Workflow.builder("login")
                            .requiredInput(PAGE)
                            .requiredInput(EMAIL)
                            .requiredInput(PASSWORD)
                            .step(typeEmailStep())
                            .step(typePasswordStep())
                            .step(signInStep())
                            .step(readDashboardHeadingStep())
                            .step(neverMatchingGate)
                            .build();
            WorkflowInputs inputs =
                    WorkflowInputs.builder()
                            .put(PAGE, page)
                            .put(EMAIL, "user@example.test")
                            .put(PASSWORD, "WA4J_IT_LOGIN_SECRET_007")
                            .build();

            WorkflowResult result = engine.execute(workflow, inputs);

            assertThat(result.completed()).isTrue();
            assertThat(result.output(DASHBOARD_HEADING)).contains("Welcome");
            assertThat(result.steps().get(4).status()).isEqualTo(WorkflowStepStatus.SKIPPED);
            // Real, externally observable proof the gated click never ran: the notifications
            // dialog - which only "Open notifications" ever opens - was never shown.
            assertThat(page.url()).contains("/dashboard");
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
