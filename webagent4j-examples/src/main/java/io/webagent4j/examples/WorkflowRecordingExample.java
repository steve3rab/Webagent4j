package io.webagent4j.examples;

import static io.webagent4j.verification.Verifications.urlContains;

import io.webagent4j.action.Secret;
import io.webagent4j.browser.IBrowser;
import io.webagent4j.browser.IPage;
import io.webagent4j.core.WebAgent;
import io.webagent4j.recording.JsonWorkflowRecordingCodec;
import io.webagent4j.recording.RecordingId;
import io.webagent4j.recording.WorkflowRecorder;
import io.webagent4j.recording.WorkflowRecording;
import io.webagent4j.recording.WorkflowReplayResult;
import io.webagent4j.recording.WorkflowReplayVerifier;
import io.webagent4j.workflow.Workflow;
import io.webagent4j.workflow.WorkflowEngine;
import io.webagent4j.workflow.WorkflowInputs;
import io.webagent4j.workflow.WorkflowResult;
import io.webagent4j.workflow.WorkflowSteps;
import io.webagent4j.workflow.WorkflowVariable;
import java.time.Instant;

/**
 * Demonstrates {@code webagent4j-recording}: capture one workflow execution into a versioned,
 * secret-safe {@link WorkflowRecording}, round-trip it through canonical JSON, then verify a second
 * independent execution against the decoded recording with {@link WorkflowReplayVerifier}.
 *
 * <p>This never replays the browser automatically - {@link WorkflowReplayVerifier} performs a pure
 * structured comparison against a {@code WorkflowResult} the caller obtained from its own new
 * {@link WorkflowEngine#execute} call, exactly as demonstrated below.
 */
public final class WorkflowRecordingExample {

    private static final WorkflowVariable<IPage> PAGE =
            WorkflowVariable.publicValue("page", IPage.class);
    private static final WorkflowVariable<String> EMAIL =
            WorkflowVariable.publicValue("email", String.class);
    private static final WorkflowVariable<String> PASSWORD = WorkflowVariable.secret("password");

    private WorkflowRecordingExample() {}

    /**
     * Runs against a page containing a labelled sign-in form, twice, and verifies a replay match.
     */
    public static void main(String[] args) {
        if (args.length == 0 || args[0].isBlank()) {
            throw new IllegalArgumentException("Expected the login page URL as the first argument");
        }
        String url = args[0];

        Workflow login =
                Workflow.builder("login")
                        .requiredInput(PAGE)
                        .requiredInput(EMAIL)
                        .requiredInput(PASSWORD)
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

        WorkflowEngine engine = new WorkflowEngine();
        WorkflowRecorder recorder = new WorkflowRecorder();
        JsonWorkflowRecordingCodec codec = new JsonWorkflowRecordingCodec();
        WorkflowReplayVerifier verifier = new WorkflowReplayVerifier();

        WorkflowRecording decodedRecording;
        try (IBrowser browser = WebAgent.browser().playwright().chromium().headless(true).launch();
                IPage page = browser.open(url)) {
            WorkflowResult firstResult =
                    engine.execute(
                            login,
                            WorkflowInputs.builder()
                                    .put(PAGE, page)
                                    .put(EMAIL, "user@example.test")
                                    .put(PASSWORD, "not-a-real-password")
                                    .build());
            firstResult.throwIfFailed();

            WorkflowRecording recording =
                    recorder.record(new RecordingId("example-run-1"), Instant.now(), firstResult);
            String encoded = codec.encode(recording);
            System.out.println(
                    "Recorded and encoded (" + encoded.length() + " bytes of canonical JSON)");

            decodedRecording = codec.decode(encoded);
        }

        try (IBrowser browser = WebAgent.browser().playwright().chromium().headless(true).launch();
                IPage page = browser.open(url)) {
            WorkflowResult secondResult =
                    engine.execute(
                            login,
                            WorkflowInputs.builder()
                                    .put(PAGE, page)
                                    .put(EMAIL, "user@example.test")
                                    .put(PASSWORD, "a-different-password-this-time")
                                    .build());
            secondResult.throwIfFailed();

            WorkflowReplayResult replay = verifier.verify(decodedRecording, secondResult);
            if (replay.matches()) {
                System.out.println("Replay verification: MATCH");
            } else {
                System.out.println("Replay verification: MISMATCH - " + replay.mismatches());
            }
        }
    }
}
