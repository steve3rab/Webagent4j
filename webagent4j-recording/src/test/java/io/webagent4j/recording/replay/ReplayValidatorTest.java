package io.webagent4j.recording.replay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.webagent4j.recording.RecordingId;
import io.webagent4j.recording.WorkflowRecorderV2;
import io.webagent4j.recording.WorkflowRecordingV2;
import io.webagent4j.workflow.Workflow;
import io.webagent4j.workflow.WorkflowConditions;
import io.webagent4j.workflow.WorkflowEngine;
import io.webagent4j.workflow.WorkflowExecution;
import io.webagent4j.workflow.WorkflowExecutionPlan;
import io.webagent4j.workflow.WorkflowInputs;
import io.webagent4j.workflow.WorkflowPlanner;
import io.webagent4j.workflow.WorkflowSteps;
import io.webagent4j.workflow.WorkflowVariable;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * RPL-VALIDATE coverage for {@link ReplayValidator}: compatibility checking of a real, recorded
 * {@link WorkflowRecordingV2} against a live {@link Workflow}, using genuine {@link WorkflowEngine}
 * executions rather than hand-built fixtures.
 */
class ReplayValidatorTest {

    private final WorkflowEngine engine = new WorkflowEngine();
    private final WorkflowRecorderV2 recorder = new WorkflowRecorderV2();

    private static Workflow linearWorkflow(String id) {
        WorkflowVariable<String> input = WorkflowVariable.publicValue("name", String.class);
        return Workflow.builder(id)
                .requiredInput(input)
                .step(
                        WorkflowSteps.assign(
                                "s1", WorkflowVariable.publicValue("out", String.class), "v"))
                .build();
    }

    private WorkflowRecordingV2 recordSuccessfulExecution(Workflow workflow) {
        WorkflowExecutionPlan plan = WorkflowPlanner.plan(workflow);
        WorkflowExecution execution =
                engine.executeWithTree(
                        workflow,
                        WorkflowInputs.builder()
                                .put(WorkflowVariable.publicValue("name", String.class), "x")
                                .build());
        return recorder.record(new RecordingId("rec"), Instant.now(), plan, execution);
    }

    @Test
    void rplValidate001MatchingWorkflowIsAccepted() {
        Workflow workflow = linearWorkflow("wf-validate-1");
        WorkflowRecordingV2 recording = recordSuccessfulExecution(workflow);

        Optional<ReplayValidationFailure> result = ReplayValidator.validate(recording, workflow);

        assertThat(result).isEmpty();
    }

    @Test
    void rplValidate002RebuiltIdenticalWorkflowIsAlsoAccepted() {
        Workflow workflow = linearWorkflow("wf-validate-2");
        WorkflowRecordingV2 recording = recordSuccessfulExecution(workflow);
        Workflow rebuilt = linearWorkflow("wf-validate-2");

        Optional<ReplayValidationFailure> result = ReplayValidator.validate(recording, rebuilt);

        assertThat(result).isEmpty();
    }

    @Test
    void rplValidate003DifferentWorkflowIdIsRejectedAsIncompatible() {
        Workflow workflow = linearWorkflow("wf-validate-3a");
        WorkflowRecordingV2 recording = recordSuccessfulExecution(workflow);
        Workflow differentId = linearWorkflow("wf-validate-3b");

        Optional<ReplayValidationFailure> result = ReplayValidator.validate(recording, differentId);

        assertThat(result).isPresent();
        assertThat(result.orElseThrow().type()).isEqualTo(ReplayFailureType.INCOMPATIBLE_WORKFLOW);
    }

    @Test
    void rplValidate004StructurallyChangedWorkflowIsRejectedAsIncompatible() {
        Workflow original = linearWorkflow("wf-validate-4");
        WorkflowRecordingV2 recording = recordSuccessfulExecution(original);

        WorkflowVariable<String> input = WorkflowVariable.publicValue("name", String.class);
        Workflow changed =
                Workflow.builder("wf-validate-4")
                        .requiredInput(input)
                        .step(
                                WorkflowSteps.assign(
                                        "s1",
                                        WorkflowVariable.publicValue("out", String.class),
                                        "v"))
                        .step(
                                WorkflowSteps.assign(
                                        "s2",
                                        WorkflowVariable.publicValue("out2", String.class),
                                        "v2"))
                        .build();

        Optional<ReplayValidationFailure> result = ReplayValidator.validate(recording, changed);

        assertThat(result).isPresent();
        assertThat(result.orElseThrow().type()).isEqualTo(ReplayFailureType.INCOMPATIBLE_WORKFLOW);
    }

    @Test
    void rplValidate005DifferentBranchStructureIsRejectedAsIncompatible() {
        WorkflowVariable<Boolean> flag = WorkflowVariable.publicValue("flag", Boolean.class);
        Workflow original =
                Workflow.builder("wf-validate-5")
                        .requiredInput(flag)
                        .step(
                                WorkflowSteps.ifThen(
                                        "branch",
                                        WorkflowConditions.isTrue(flag),
                                        List.of(
                                                WorkflowSteps.assign(
                                                        "then",
                                                        WorkflowVariable.publicValue(
                                                                "out", String.class),
                                                        "v"))))
                        .build();
        WorkflowExecutionPlan plan = WorkflowPlanner.plan(original);
        WorkflowExecution execution =
                engine.executeWithTree(original, WorkflowInputs.builder().put(flag, true).build());
        WorkflowRecordingV2 recording =
                recorder.record(new RecordingId("rec-5"), Instant.now(), plan, execution);

        Workflow withElse =
                Workflow.builder("wf-validate-5")
                        .requiredInput(flag)
                        .step(
                                WorkflowSteps.ifElse(
                                        "branch",
                                        WorkflowConditions.isTrue(flag),
                                        List.of(
                                                WorkflowSteps.assign(
                                                        "then",
                                                        WorkflowVariable.publicValue(
                                                                "out", String.class),
                                                        "v")),
                                        List.of(
                                                WorkflowSteps.assign(
                                                        "else",
                                                        WorkflowVariable.publicValue(
                                                                "out", String.class),
                                                        "v2"))))
                        .build();

        Optional<ReplayValidationFailure> result = ReplayValidator.validate(recording, withElse);

        assertThat(result).isPresent();
        assertThat(result.orElseThrow().type()).isEqualTo(ReplayFailureType.INCOMPATIBLE_WORKFLOW);
    }

    @Test
    void rplValidate006FailedRecordingIsRejectedAsUnsupportedStatus() {
        Workflow workflow =
                Workflow.builder("wf-validate-6")
                        .step(
                                WorkflowSteps.action(
                                        "s1",
                                        vars -> {
                                            throw new RuntimeException("boom");
                                        }))
                        .build();
        WorkflowExecutionPlan plan = WorkflowPlanner.plan(workflow);
        WorkflowExecution execution = engine.executeWithTree(workflow, WorkflowInputs.empty());
        assertThat(execution.result().completed()).isFalse();
        WorkflowRecordingV2 recording =
                recorder.record(new RecordingId("rec-6"), Instant.now(), plan, execution);

        Optional<ReplayValidationFailure> result = ReplayValidator.validate(recording, workflow);

        assertThat(result).isPresent();
        assertThat(result.orElseThrow().type()).isEqualTo(ReplayFailureType.UNSUPPORTED_STATUS);
    }

    @Test
    void rplValidate007PreflightFailedRecordingIsRejectedAsUnsupportedStatus() {
        WorkflowVariable<String> required = WorkflowVariable.publicValue("required", String.class);
        Workflow workflow =
                Workflow.builder("wf-validate-7")
                        .requiredInput(required)
                        .step(
                                WorkflowSteps.assign(
                                        "s1",
                                        WorkflowVariable.publicValue("v1", String.class),
                                        "x"))
                        .build();
        WorkflowExecutionPlan plan = WorkflowPlanner.plan(workflow);
        WorkflowExecution execution = engine.executeWithTree(workflow, WorkflowInputs.empty());
        WorkflowRecordingV2 recording =
                recorder.record(new RecordingId("rec-7"), Instant.now(), plan, execution);

        Optional<ReplayValidationFailure> result = ReplayValidator.validate(recording, workflow);

        assertThat(result).isPresent();
        assertThat(result.orElseThrow().type()).isEqualTo(ReplayFailureType.UNSUPPORTED_STATUS);
    }

    @Test
    void rplValidate008NullRecordingIsRejected() {
        Workflow workflow = linearWorkflow("wf-validate-8");

        assertThatThrownBy(() -> ReplayValidator.validate(null, workflow))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void rplValidate009NullWorkflowIsRejected() {
        Workflow workflow = linearWorkflow("wf-validate-9");
        WorkflowRecordingV2 recording = recordSuccessfulExecution(workflow);

        assertThatThrownBy(() -> ReplayValidator.validate(recording, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void rplValidate010NestedBranchingWorkflowIsAccepted() {
        WorkflowVariable<Boolean> a = WorkflowVariable.publicValue("a", Boolean.class);
        WorkflowVariable<Boolean> b = WorkflowVariable.publicValue("b", Boolean.class);
        Workflow workflow =
                Workflow.builder("wf-validate-10")
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
                                                                        "x",
                                                                        WorkflowVariable
                                                                                .publicValue(
                                                                                        "xout",
                                                                                        String
                                                                                                .class),
                                                                        "x-val")),
                                                        List.of(
                                                                WorkflowSteps.assign(
                                                                        "y",
                                                                        WorkflowVariable
                                                                                .publicValue(
                                                                                        "yout",
                                                                                        String
                                                                                                .class),
                                                                        "y-val")))),
                                        List.of(
                                                WorkflowSteps.assign(
                                                        "z",
                                                        WorkflowVariable.publicValue(
                                                                "zout", String.class),
                                                        "z-val"))))
                        .build();
        WorkflowExecutionPlan plan = WorkflowPlanner.plan(workflow);
        WorkflowExecution execution =
                engine.executeWithTree(
                        workflow, WorkflowInputs.builder().put(a, true).put(b, false).build());
        WorkflowRecordingV2 recording =
                recorder.record(new RecordingId("rec-10"), Instant.now(), plan, execution);

        Optional<ReplayValidationFailure> result = ReplayValidator.validate(recording, workflow);

        assertThat(result).isEmpty();
    }
}
