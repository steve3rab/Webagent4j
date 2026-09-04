package io.webagent4j.recording;

import static org.assertj.core.api.Assertions.assertThat;

import io.webagent4j.workflow.Workflow;
import io.webagent4j.workflow.WorkflowConditions;
import io.webagent4j.workflow.WorkflowEngine;
import io.webagent4j.workflow.WorkflowInputs;
import io.webagent4j.workflow.WorkflowResult;
import io.webagent4j.workflow.WorkflowStepStatus;
import io.webagent4j.workflow.WorkflowStepType;
import io.webagent4j.workflow.WorkflowSteps;
import io.webagent4j.workflow.WorkflowVariable;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Recording V1 compatibility for {@code CONDITIONAL} steps: since {@code WorkflowResult.steps()}
 * stays one flat, execution-ordered list - the conditional step's own decision, immediately
 * followed by whichever single branch it selected, exactly the same shape a plain sequential step
 * list has always had - the existing recorder/codec require no format change to capture a branching
 * execution faithfully, including a false decision (never {@code SKIPPED} for this step type) and
 * nested branching.
 */
class WorkflowBranchingRecordingTest {

    private final WorkflowEngine engine = new WorkflowEngine();
    private final WorkflowRecorder recorder = new WorkflowRecorder();
    private final JsonWorkflowRecordingCodec codec = new JsonWorkflowRecordingCodec();

    @Test
    void trueDecisionRoundTripsThroughJsonWithTheSelectedBranchInline() {
        WorkflowVariable<Boolean> flag = WorkflowVariable.publicValue("flag", Boolean.class);
        Workflow workflow =
                Workflow.builder("wf-branch-true")
                        .requiredInput(flag)
                        .step(
                                WorkflowSteps.ifElse(
                                        "branch",
                                        WorkflowConditions.isTrue(flag),
                                        List.of(
                                                WorkflowSteps.assign(
                                                        "then", value("t"), "then-val")),
                                        List.of(
                                                WorkflowSteps.assign(
                                                        "else", value("e"), "else-val"))))
                        .build();
        WorkflowResult result =
                engine.execute(workflow, WorkflowInputs.builder().put(flag, true).build());
        assertThat(result.completed()).isTrue();

        WorkflowRecording recording =
                recorder.record(
                        new RecordingId("rec-branch-true"),
                        Instant.parse("2026-01-01T00:00:00Z"),
                        result);
        String json = codec.encode(recording);
        WorkflowRecording decoded = codec.decode(json);

        assertThat(decoded).isEqualTo(recording);
        assertThat(decoded.steps()).hasSize(2);
        assertThat(decoded.steps().get(0).stepType()).isEqualTo(WorkflowStepType.CONDITIONAL);
        assertThat(decoded.steps().get(0).status()).isEqualTo(WorkflowStepStatus.SUCCEEDED);
        assertThat(decoded.steps().get(0).condition().orElseThrow().outcome()).isTrue();
        assertThat(decoded.steps().get(1).stepId().value()).isEqualTo("then");
    }

    @Test
    void falseDecisionRoundTripsAndIsNeverRecordedAsSkipped() {
        WorkflowVariable<Boolean> flag = WorkflowVariable.publicValue("flag", Boolean.class);
        Workflow workflow =
                Workflow.builder("wf-branch-false")
                        .requiredInput(flag)
                        .step(
                                WorkflowSteps.ifElse(
                                        "branch",
                                        WorkflowConditions.isTrue(flag),
                                        List.of(
                                                WorkflowSteps.assign(
                                                        "then", value("t"), "then-val")),
                                        List.of(
                                                WorkflowSteps.assign(
                                                        "else", value("e"), "else-val"))))
                        .build();
        WorkflowResult result =
                engine.execute(workflow, WorkflowInputs.builder().put(flag, false).build());
        assertThat(result.completed()).isTrue();

        WorkflowRecording recording =
                recorder.record(
                        new RecordingId("rec-branch-false"),
                        Instant.parse("2026-01-01T00:00:00Z"),
                        result);
        String json = codec.encode(recording);
        WorkflowRecording decoded = codec.decode(json);

        assertThat(decoded).isEqualTo(recording);
        assertThat(decoded.steps().get(0).status()).isEqualTo(WorkflowStepStatus.SUCCEEDED);
        assertThat(decoded.steps().get(0).condition().orElseThrow().outcome()).isFalse();
        assertThat(decoded.steps().get(1).stepId().value()).isEqualTo("else");
    }

    @Test
    void nestedBranchingRoundTripsWithEveryLevelInline() {
        WorkflowVariable<Boolean> a = WorkflowVariable.publicValue("a", Boolean.class);
        WorkflowVariable<Boolean> b = WorkflowVariable.publicValue("b", Boolean.class);
        Workflow workflow =
                Workflow.builder("wf-nested")
                        .requiredInput(a)
                        .requiredInput(b)
                        .step(
                                WorkflowSteps.ifElse(
                                        "outer",
                                        WorkflowConditions.isTrue(a),
                                        List.of(
                                                WorkflowSteps.ifElse(
                                                        "inner",
                                                        WorkflowConditions.isTrue(b),
                                                        List.of(
                                                                WorkflowSteps.assign(
                                                                        "x", value("x"), "x-val")),
                                                        List.of(
                                                                WorkflowSteps.assign(
                                                                        "y",
                                                                        value("y"),
                                                                        "y-val")))),
                                        List.of(WorkflowSteps.assign("z", value("z"), "z-val"))))
                        .build();
        WorkflowResult result =
                engine.execute(
                        workflow, WorkflowInputs.builder().put(a, true).put(b, false).build());
        assertThat(result.completed()).isTrue();

        WorkflowRecording recording =
                recorder.record(
                        new RecordingId("rec-nested"),
                        Instant.parse("2026-01-01T00:00:00Z"),
                        result);
        String json = codec.encode(recording);
        WorkflowRecording decoded = codec.decode(json);

        assertThat(decoded).isEqualTo(recording);
        assertThat(decoded.steps().stream().map(s -> s.stepId().value()))
                .containsExactly("outer", "inner", "y");
    }

    private static WorkflowVariable<String> value(String name) {
        return WorkflowVariable.publicValue(name, String.class);
    }
}
