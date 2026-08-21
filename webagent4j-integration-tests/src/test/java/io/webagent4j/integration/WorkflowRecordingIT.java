package io.webagent4j.integration;

import static io.webagent4j.verification.Verifications.textVisible;
import static io.webagent4j.verification.Verifications.urlContains;
import static org.assertj.core.api.Assertions.assertThat;

import io.webagent4j.action.Secret;
import io.webagent4j.browser.IPage;
import io.webagent4j.recording.JsonWorkflowRecordingCodec;
import io.webagent4j.recording.RecordingId;
import io.webagent4j.recording.WorkflowRecorder;
import io.webagent4j.recording.WorkflowRecording;
import io.webagent4j.recording.WorkflowReplayResult;
import io.webagent4j.recording.WorkflowReplayVerifier;
import io.webagent4j.workflow.IWorkflowStep;
import io.webagent4j.workflow.Workflow;
import io.webagent4j.workflow.WorkflowEngine;
import io.webagent4j.workflow.WorkflowInputs;
import io.webagent4j.workflow.WorkflowResult;
import io.webagent4j.workflow.WorkflowSteps;
import io.webagent4j.workflow.WorkflowVariable;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * Real-Playwright integration coverage for {@code webagent4j-recording}: records an actual login
 * execution against {@link ActionTestApplication}'s deterministic {@code /login} -> {@code
 * /dashboard} fixture, round-trips it through the JSON codec, then verifies a second, independent
 * real execution against the decoded recording - proving replay verification works end to end
 * against genuine action pipeline output, not just hand-built fixtures.
 */
class WorkflowRecordingIT {

    private static final WorkflowVariable<IPage> PAGE =
            WorkflowVariable.publicValue("page", IPage.class);
    private static final WorkflowVariable<String> EMAIL =
            WorkflowVariable.publicValue("email", String.class);
    private static final WorkflowVariable<String> PASSWORD = WorkflowVariable.secret("password");

    private final WorkflowEngine engine = new WorkflowEngine();
    private final WorkflowRecorder recorder = new WorkflowRecorder();
    private final JsonWorkflowRecordingCodec codec = new JsonWorkflowRecordingCodec();
    private final WorkflowReplayVerifier verifier = new WorkflowReplayVerifier();

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

    private static Workflow loginWorkflow() {
        return Workflow.builder("login")
                .requiredInput(PAGE)
                .requiredInput(EMAIL)
                .requiredInput(PASSWORD)
                .step(typeEmailStep())
                .step(typePasswordStep())
                .step(signInStep())
                .build();
    }

    @Test
    void recordingIt001RecordEncodeDecodeAndVerifyAgainstAFreshRealExecution() throws Exception {
        String sentinel = "WA4J_IT_RECORDING_SECRET_DO_NOT_LEAK";
        Workflow workflow = loginWorkflow();

        WorkflowRecording decodedRecording;
        try (var support = Phase4TestSupport.start();
                var firstPage = support.open("/login")) {
            WorkflowResult firstResult =
                    engine.execute(
                            workflow,
                            WorkflowInputs.builder()
                                    .put(PAGE, firstPage)
                                    .put(EMAIL, "first@example.test")
                                    .put(PASSWORD, sentinel)
                                    .build());
            assertThat(firstResult.completed()).isTrue();

            WorkflowRecording recording =
                    recorder.record(new RecordingId("it-recording-1"), Instant.now(), firstResult);
            String encoded = codec.encode(recording);

            assertThat(encoded).doesNotContain(sentinel);

            decodedRecording = codec.decode(encoded);
            assertThat(decodedRecording).isEqualTo(recording);
        }

        try (var support = Phase4TestSupport.start();
                var secondPage = support.open("/login")) {
            WorkflowResult secondResult =
                    engine.execute(
                            workflow,
                            WorkflowInputs.builder()
                                    .put(PAGE, secondPage)
                                    .put(EMAIL, "second@example.test")
                                    .put(PASSWORD, "WA4J_IT_RECORDING_SECRET_SECOND_RUN")
                                    .build());
            assertThat(secondResult.completed()).isTrue();

            for (int i = 0; i < decodedRecording.steps().size(); i++) {
                assertThat(secondResult.steps().get(i).actionSummary().orElseThrow().actionId())
                        .isNotEqualTo(
                                decodedRecording.steps().get(i).action().orElseThrow().actionId());
            }

            WorkflowReplayResult replay = verifier.verify(decodedRecording, secondResult);

            assertThat(replay.matches()).isTrue();
            assertThat(replay.mismatches()).isEmpty();
        }
    }
}
