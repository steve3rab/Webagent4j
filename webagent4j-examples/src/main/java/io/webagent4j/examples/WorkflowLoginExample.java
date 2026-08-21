package io.webagent4j.examples;

import static io.webagent4j.verification.Verifications.urlContains;

import io.webagent4j.action.Secret;
import io.webagent4j.browser.IBrowser;
import io.webagent4j.browser.IPage;
import io.webagent4j.core.WebAgent;
import io.webagent4j.workflow.Workflow;
import io.webagent4j.workflow.WorkflowConditions;
import io.webagent4j.workflow.WorkflowEngine;
import io.webagent4j.workflow.WorkflowInputs;
import io.webagent4j.workflow.WorkflowResult;
import io.webagent4j.workflow.WorkflowSteps;
import io.webagent4j.workflow.WorkflowVariable;

/**
 * Demonstrates a deterministic, sequential {@code webagent4j-workflow} definition: typed public and
 * secret variables, required inputs, a conditionally skipped step, real action-pipeline
 * integration, and a structured, secret-masked result.
 */
public final class WorkflowLoginExample {

    private static final WorkflowVariable<IPage> PAGE =
            WorkflowVariable.publicValue("page", IPage.class);
    private static final WorkflowVariable<String> EMAIL =
            WorkflowVariable.publicValue("email", String.class);
    private static final WorkflowVariable<String> PASSWORD = WorkflowVariable.secret("password");
    private static final WorkflowVariable<Boolean> REMEMBER =
            WorkflowVariable.publicValue("remember", Boolean.class);

    private WorkflowLoginExample() {}

    /** Runs against a page containing a labelled sign-in form with a "Remember me" checkbox. */
    public static void main(String[] args) {
        if (args.length == 0 || args[0].isBlank()) {
            throw new IllegalArgumentException("Expected the login page URL as the first argument");
        }

        Workflow login =
                Workflow.builder("login")
                        .requiredInput(PAGE)
                        .requiredInput(EMAIL)
                        .requiredInput(PASSWORD)
                        .optionalInput(REMEMBER)
                        .step(
                                WorkflowSteps.action(
                                        "type-email",
                                        vars -> {
                                            IPage page = vars.require(PAGE);
                                            var email =
                                                    page.find()
                                                            .textbox()
                                                            .labelled("Email")
                                                            .single();
                                            return page.action().type(email, vars.require(EMAIL));
                                        }))
                        .step(
                                WorkflowSteps.action(
                                        "type-password",
                                        vars -> {
                                            IPage page = vars.require(PAGE);
                                            var password =
                                                    page.find()
                                                            .textbox()
                                                            .labelled("Password")
                                                            .single();
                                            return page.action()
                                                    .typeSecret(
                                                            password,
                                                            Secret.of(vars.require(PASSWORD)));
                                        }))
                        .step(
                                WorkflowSteps.action(
                                                "check-remember",
                                                vars -> {
                                                    IPage page = vars.require(PAGE);
                                                    var remember =
                                                            page.find()
                                                                    .checkbox()
                                                                    .named("Remember me")
                                                                    .single();
                                                    return page.action().check(remember);
                                                })
                                        .when(WorkflowConditions.isTrue(REMEMBER)))
                        .step(
                                WorkflowSteps.action(
                                        "sign-in",
                                        vars -> {
                                            IPage page = vars.require(PAGE);
                                            var signIn =
                                                    page.find().button().named("Sign in").single();
                                            return page.action()
                                                    .click(signIn)
                                                    .expect(urlContains("/dashboard"));
                                        }))
                        .build();

        try (IBrowser browser = WebAgent.browser().playwright().chromium().headless(true).launch();
                IPage page = browser.open(args[0])) {
            WorkflowInputs inputs =
                    WorkflowInputs.builder()
                            .put(PAGE, page)
                            .put(EMAIL, "user@example.test")
                            .put(PASSWORD, "not-a-real-password")
                            .put(REMEMBER, false)
                            .build();

            WorkflowResult result = new WorkflowEngine().execute(login, inputs);

            System.out.println(result);
            result.throwIfFailed();
        }
    }
}
