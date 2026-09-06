package io.webagent4j.recording;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.webagent4j.action.ActionFailureType;
import io.webagent4j.action.ActionType;
import io.webagent4j.workflow.Workflow;
import io.webagent4j.workflow.WorkflowBranchSelection;
import io.webagent4j.workflow.WorkflowConditions;
import io.webagent4j.workflow.WorkflowEngine;
import io.webagent4j.workflow.WorkflowExecution;
import io.webagent4j.workflow.WorkflowExecutionPlan;
import io.webagent4j.workflow.WorkflowFailureType;
import io.webagent4j.workflow.WorkflowInputs;
import io.webagent4j.workflow.WorkflowPlanner;
import io.webagent4j.workflow.WorkflowStatus;
import io.webagent4j.workflow.WorkflowStepStatus;
import io.webagent4j.workflow.WorkflowStepType;
import io.webagent4j.workflow.WorkflowSteps;
import io.webagent4j.workflow.WorkflowVariable;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * REC2-CAPTURE coverage for {@link WorkflowRecorderV2}: capturing a real {@code WorkflowExecution}
 * (produced by the actual {@link WorkflowEngine}, never a hand-built fixture) into a {@link
 * WorkflowRecordingV2} that round-trips through {@link JsonWorkflowRecordingV2Codec}, mirroring
 * {@link WorkflowRecorderTest} and {@link WorkflowBranchingRecordingTest}'s style for V1.
 */
class WorkflowRecorderV2Test {

    private final WorkflowEngine engine = new WorkflowEngine();
    private final WorkflowRecorderV2 recorder = new WorkflowRecorderV2();
    private final JsonWorkflowRecordingV2Codec codec = new JsonWorkflowRecordingV2Codec();

    @Test
    void capturesAndRoundTripsALinearSuccessfulExecution() {
        WorkflowVariable<String> input = WorkflowVariable.publicValue("name", String.class);
        WorkflowVariable<String> actionOutput =
                WorkflowVariable.publicValue("actionOut", String.class);
        WorkflowVariable<String> assignOutput =
                WorkflowVariable.publicValue("assignOut", String.class);
        Workflow workflow =
                Workflow.builder("wf-v2-linear")
                        .requiredInput(input)
                        .step(
                                WorkflowSteps.action(
                                        "s1",
                                        vars ->
                                                new FakePreparedAction<>(
                                                        ActionResults.success("clicked")),
                                        actionOutput))
                        .step(WorkflowSteps.assign("s2", assignOutput, "literal"))
                        .build();
        WorkflowExecutionPlan plan = WorkflowPlanner.plan(workflow);
        WorkflowExecution execution =
                engine.executeWithTree(
                        workflow, WorkflowInputs.builder().put(input, "value").build());
        assertThat(execution.result().completed()).isTrue();

        Instant capturedAt = Instant.parse("2026-01-01T00:00:00Z");
        WorkflowRecordingV2 recording =
                recorder.record(new RecordingId("rec-v2-linear"), capturedAt, plan, execution);

        assertThat(recording.schemaVersion()).isEqualTo(RecordingSchemaVersionV2.V2);
        assertThat(recording.status()).isEqualTo(WorkflowStatus.COMPLETED);
        assertThat(recording.plan()).isEqualTo(plan);
        assertThat(recording.nodes()).hasSize(2);
        assertThat(recording.nodes().get(0).step().stepType()).isEqualTo(WorkflowStepType.ACTION);
        assertThat(recording.nodes().get(0).step().output())
                .contains(
                        new io.webagent4j.workflow.WorkflowPlanOutput(
                                "actionOut", "String", false));
        assertThat(recording.nodes().get(0).step().action().orElseThrow().actionType())
                .isEqualTo(ActionType.CLICK);
        assertThat(recording.nodes().get(1).step().output())
                .contains(
                        new io.webagent4j.workflow.WorkflowPlanOutput(
                                "assignOut", "String", false));

        WorkflowRecordingV2 decoded = codec.decode(codec.encode(recording));
        assertThat(decoded).isEqualTo(recording);
    }

    @Test
    void capturesTheSelectedThenBranchWithNestedChildren() {
        WorkflowVariable<Boolean> flag = WorkflowVariable.publicValue("flag", Boolean.class);
        Workflow workflow =
                Workflow.builder("wf-v2-branch-true")
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
                                                        "then-val")),
                                        List.of(
                                                WorkflowSteps.assign(
                                                        "else",
                                                        WorkflowVariable.publicValue(
                                                                "out", String.class),
                                                        "else-val"))))
                        .build();
        WorkflowExecutionPlan plan = WorkflowPlanner.plan(workflow);
        WorkflowExecution execution =
                engine.executeWithTree(workflow, WorkflowInputs.builder().put(flag, true).build());
        assertThat(execution.result().completed()).isTrue();

        WorkflowRecordingV2 recording =
                recorder.record(
                        new RecordingId("rec-v2-branch-true"),
                        Instant.parse("2026-01-01T00:00:00Z"),
                        plan,
                        execution);

        assertThat(recording.nodes()).hasSize(1);
        RecordedExecutionNodeV2 conditionalNode = recording.nodes().get(0);
        assertThat(conditionalNode.step().stepType()).isEqualTo(WorkflowStepType.CONDITIONAL);
        assertThat(conditionalNode.branchSelection()).contains(WorkflowBranchSelection.THEN);
        assertThat(conditionalNode.children()).hasSize(1);
        assertThat(conditionalNode.children().get(0).step().stepId().value()).isEqualTo("then");

        WorkflowRecordingV2 decoded = codec.decode(codec.encode(recording));
        assertThat(decoded).isEqualTo(recording);
    }

    @Test
    void capturesTheSelectedElseBranchAndNeverRecordsTheThenBranch() {
        WorkflowVariable<Boolean> flag = WorkflowVariable.publicValue("flag", Boolean.class);
        Workflow workflow =
                Workflow.builder("wf-v2-branch-false")
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
                                                        "then-val")),
                                        List.of(
                                                WorkflowSteps.assign(
                                                        "else",
                                                        WorkflowVariable.publicValue(
                                                                "out", String.class),
                                                        "else-val"))))
                        .build();
        WorkflowExecutionPlan plan = WorkflowPlanner.plan(workflow);
        WorkflowExecution execution =
                engine.executeWithTree(workflow, WorkflowInputs.builder().put(flag, false).build());

        WorkflowRecordingV2 recording =
                recorder.record(
                        new RecordingId("rec-v2-branch-false"),
                        Instant.parse("2026-01-01T00:00:00Z"),
                        plan,
                        execution);

        RecordedExecutionNodeV2 conditionalNode = recording.nodes().get(0);
        assertThat(conditionalNode.branchSelection()).contains(WorkflowBranchSelection.ELSE);
        assertThat(conditionalNode.children()).hasSize(1);
        assertThat(conditionalNode.children().get(0).step().stepId().value()).isEqualTo("else");
        // The plan still describes both branches structurally - only the execution tree is
        // selection-only.
        assertThat(recording.plan().nodes().get(0).branches()).hasSize(2);
    }

    @Test
    void capturesNestedBranching() {
        WorkflowVariable<Boolean> a = WorkflowVariable.publicValue("a", Boolean.class);
        WorkflowVariable<Boolean> b = WorkflowVariable.publicValue("b", Boolean.class);
        Workflow workflow =
                Workflow.builder("wf-v2-nested")
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
                recorder.record(
                        new RecordingId("rec-v2-nested"),
                        Instant.parse("2026-01-01T00:00:00Z"),
                        plan,
                        execution);

        RecordedExecutionNodeV2 outer = recording.nodes().get(0);
        assertThat(outer.step().stepId().value()).isEqualTo("outer");
        assertThat(outer.branchSelection()).contains(WorkflowBranchSelection.THEN);
        RecordedExecutionNodeV2 inner = outer.children().get(0);
        assertThat(inner.step().stepId().value()).isEqualTo("inner");
        assertThat(inner.branchSelection()).contains(WorkflowBranchSelection.ELSE);
        assertThat(inner.children().get(0).step().stepId().value()).isEqualTo("y");

        WorkflowRecordingV2 decoded = codec.decode(codec.encode(recording));
        assertThat(decoded).isEqualTo(recording);
    }

    @Test
    void capturesASecretOutputClassificationWithoutEverRecordingTheValue() {
        String sentinel = "WA4J_RECORDER_V2_SAFE_SENTINEL_58213";
        WorkflowVariable<String> secretOut = WorkflowVariable.secret("secretOut");
        Workflow workflow =
                Workflow.builder("wf-v2-secret")
                        .step(
                                WorkflowSteps.action(
                                        "s1",
                                        vars ->
                                                new FakePreparedAction<>(
                                                        ActionResults.success(sentinel)),
                                        secretOut))
                        .build();
        WorkflowExecutionPlan plan = WorkflowPlanner.plan(workflow);
        WorkflowExecution execution = engine.executeWithTree(workflow, WorkflowInputs.empty());
        assertThat(execution.result().completed()).isTrue();
        assertThat(execution.result().output(secretOut)).contains(sentinel);

        WorkflowRecordingV2 recording =
                recorder.record(new RecordingId("rec-v2-secret"), Instant.now(), plan, execution);

        assertThat(recording.nodes().get(0).step().output().orElseThrow().secret()).isTrue();
        assertThat(recording.toString()).doesNotContain(sentinel);
        String encoded = codec.encode(recording);
        assertThat(encoded).doesNotContain(sentinel);
        assertThat(encoded).contains("\"secret\":true");
    }

    @Test
    void capturesAFailedExecutionInsideASelectedBranch() {
        WorkflowVariable<Boolean> flag = WorkflowVariable.publicValue("flag", Boolean.class);
        Workflow workflow =
                Workflow.builder("wf-v2-branch-fail")
                        .requiredInput(flag)
                        .step(
                                WorkflowSteps.ifThen(
                                        "branch",
                                        WorkflowConditions.isTrue(flag),
                                        List.of(
                                                WorkflowSteps.action(
                                                        "boom",
                                                        vars ->
                                                                new FakePreparedAction<>(
                                                                        ActionResults.failure(
                                                                                ActionFailureType
                                                                                        .TARGET_NOT_FOUND,
                                                                                "not found"))))))
                        .build();
        WorkflowExecutionPlan plan = WorkflowPlanner.plan(workflow);
        WorkflowExecution execution =
                engine.executeWithTree(workflow, WorkflowInputs.builder().put(flag, true).build());
        assertThat(execution.result().completed()).isFalse();

        WorkflowRecordingV2 recording =
                recorder.record(
                        new RecordingId("rec-v2-branch-fail"), Instant.now(), plan, execution);

        assertThat(recording.status()).isEqualTo(WorkflowStatus.FAILED);
        assertThat(recording.failure().orElseThrow().type())
                .isEqualTo(WorkflowFailureType.ACTION_FAILED);
        RecordedExecutionNodeV2 conditionalNode = recording.nodes().get(0);
        assertThat(conditionalNode.children().get(0).step().status())
                .isEqualTo(WorkflowStepStatus.FAILED);

        WorkflowRecordingV2 decoded = codec.decode(codec.encode(recording));
        assertThat(decoded).isEqualTo(recording);
    }

    @Test
    void capturesAPreflightFailureWithNoEnteredBranch() {
        WorkflowVariable<String> required = WorkflowVariable.publicValue("required", String.class);
        Workflow workflow =
                Workflow.builder("wf-v2-preflight")
                        .requiredInput(required)
                        .step(
                                WorkflowSteps.assign(
                                        "s1",
                                        WorkflowVariable.publicValue("v1", String.class),
                                        "x"))
                        .build();
        WorkflowExecutionPlan plan = WorkflowPlanner.plan(workflow);
        WorkflowExecution execution = engine.executeWithTree(workflow, WorkflowInputs.empty());
        assertThat(execution.result().completed()).isFalse();

        WorkflowRecordingV2 recording =
                recorder.record(
                        new RecordingId("rec-v2-preflight"), Instant.now(), plan, execution);

        assertThat(recording.status()).isEqualTo(WorkflowStatus.FAILED);
        assertThat(recording.nodes()).hasSize(1);
        assertThat(recording.nodes().get(0).step().status()).isEqualTo(WorkflowStepStatus.NOT_RUN);
        assertThat(recording.nodes().get(0).children()).isEmpty();

        WorkflowRecordingV2 decoded = codec.decode(codec.encode(recording));
        assertThat(decoded).isEqualTo(recording);
    }

    /**
     * DECISION-014: an {@code ifThen} whose condition evaluates {@code false} records the
     * structural {@code NONE} decision (a no-op success, never {@code ELSE}, which does not exist
     * for this step) with zero children - the real, engine-produced counterpart to {@link
     * RecordingV2ConditionalDecisionTest#decision007FalseOutcomeWithNoneOnAnIfThenIsAccepted()}'s
     * hand-built fixture.
     */
    @Test
    void capturesAnIfThenFalseDecisionAsNone() {
        WorkflowVariable<Boolean> flag = WorkflowVariable.publicValue("flag", Boolean.class);
        Workflow workflow =
                Workflow.builder("wf-v2-ifthen-none")
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
                                                        "then-val"))))
                        .build();
        WorkflowExecutionPlan plan = WorkflowPlanner.plan(workflow);
        WorkflowExecution execution =
                engine.executeWithTree(workflow, WorkflowInputs.builder().put(flag, false).build());
        assertThat(execution.result().completed()).isTrue();

        WorkflowRecordingV2 recording =
                recorder.record(
                        new RecordingId("rec-v2-ifthen-none"),
                        Instant.parse("2026-01-01T00:00:00Z"),
                        plan,
                        execution);

        RecordedExecutionNodeV2 conditionalNode = recording.nodes().get(0);
        assertThat(conditionalNode.branchSelection()).contains(WorkflowBranchSelection.NONE);
        assertThat(conditionalNode.children()).isEmpty();

        WorkflowRecordingV2 decoded = codec.decode(codec.encode(recording));
        assertThat(decoded).isEqualTo(recording);
    }

    @Test
    void rejectsAPlanThatDoesNotDeclareAnOutputTheExecutionPublished() {
        WorkflowVariable<String> output = WorkflowVariable.publicValue("out", String.class);
        Workflow workflow =
                Workflow.builder("wf-v2-mismatch")
                        .step(WorkflowSteps.assign("s1", output, "literal"))
                        .build();
        WorkflowExecution execution = engine.executeWithTree(workflow, WorkflowInputs.empty());
        // A same-workflowId plan whose one node declares no output at all - structurally impossible
        // for the real WorkflowPlanner to produce for this workflow, but exactly what a caller
        // passing a stale/mismatched plan could supply.
        WorkflowExecutionPlan mismatchedPlan =
                new WorkflowExecutionPlan(
                        workflow.id(),
                        List.of(
                                new io.webagent4j.workflow.WorkflowPlanNode(
                                        new io.webagent4j.workflow.WorkflowStepId("s1"),
                                        WorkflowStepType.ASSIGN,
                                        false,
                                        java.util.Optional.empty(),
                                        List.of())));

        assertThatThrownBy(
                        () ->
                                recorder.record(
                                        new RecordingId("rec-v2-mismatch"),
                                        Instant.now(),
                                        mismatchedPlan,
                                        execution))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("plan and execution must describe the same workflow");
    }
}
