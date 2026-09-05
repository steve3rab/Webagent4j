package io.webagent4j.recording;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.webagent4j.action.ActionFailureType;
import io.webagent4j.workflow.IWorkflowStep;
import io.webagent4j.workflow.Workflow;
import io.webagent4j.workflow.WorkflowBranchSelection;
import io.webagent4j.workflow.WorkflowEngine;
import io.webagent4j.workflow.WorkflowExecution;
import io.webagent4j.workflow.WorkflowExecutionPlan;
import io.webagent4j.workflow.WorkflowInputs;
import io.webagent4j.workflow.WorkflowPlanner;
import io.webagent4j.workflow.WorkflowStatus;
import io.webagent4j.workflow.WorkflowStepStatus;
import io.webagent4j.workflow.WorkflowStepType;
import io.webagent4j.workflow.WorkflowSteps;
import io.webagent4j.workflow.WorkflowVariable;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * REC2-PAR coverage: capturing a real {@code PARALLEL} {@code WorkflowExecution} into {@link
 * WorkflowRecordingV2}, round-tripping it through {@link JsonWorkflowRecordingV2Codec}, and
 * rejecting hostile/malformed parallel shapes at construction time. See {@code
 * docs/recording.md#parallel}.
 */
class WorkflowParallelRecordingV2Test {

    private final WorkflowEngine engine = new WorkflowEngine();
    private final WorkflowRecorderV2 recorder = new WorkflowRecorderV2();
    private final JsonWorkflowRecordingV2Codec codec = new JsonWorkflowRecordingV2Codec();

    private static IWorkflowStepFactory safeAction(String id, WorkflowVariable<String> output) {
        return () ->
                WorkflowSteps.action(
                        id,
                        new ParallelSafeActionFactory<String>(
                                variables ->
                                        new FakePreparedAction<>(
                                                ActionResults.success(id + "-value"))),
                        output);
    }

    @FunctionalInterface
    private interface IWorkflowStepFactory {
        IWorkflowStep build();
    }

    // --- REC2-PAR-001: two successful branches capture and round-trip correctly ------------

    @Test
    void capturesAndRoundTripsTwoSuccessfulBranches() {
        WorkflowVariable<String> outA = WorkflowVariable.publicValue("outA", String.class);
        WorkflowVariable<String> outB = WorkflowVariable.publicValue("outB", String.class);
        Workflow workflow =
                Workflow.builder("wf-par")
                        .step(
                                WorkflowSteps.parallel(
                                        "par",
                                        List.of(
                                                List.of(safeAction("a", outA).build()),
                                                List.of(safeAction("b", outB).build()))))
                        .build();
        WorkflowExecutionPlan plan = WorkflowPlanner.plan(workflow);
        WorkflowExecution execution = engine.executeWithTree(workflow, WorkflowInputs.empty());
        assertThat(execution.result().completed()).isTrue();

        WorkflowRecordingV2 recording =
                recorder.record(
                        new RecordingId("rec-par"),
                        Instant.parse("2026-01-01T00:00:00Z"),
                        plan,
                        execution);

        assertThat(recording.nodes()).hasSize(1);
        RecordedExecutionNodeV2 wrapper = recording.nodes().get(0);
        assertThat(wrapper.step().stepType()).isEqualTo(WorkflowStepType.PARALLEL);
        assertThat(wrapper.branchSelection()).isEmpty();
        assertThat(wrapper.children()).hasSize(2);

        RecordedExecutionNodeV2 branch0 = wrapper.children().get(0);
        assertThat(branch0.step().stepType()).isEqualTo(WorkflowStepType.PARALLEL_BRANCH);
        assertThat(branch0.step().stepId().value()).isEqualTo("par@0");
        assertThat(branch0.children()).hasSize(1);
        assertThat(branch0.children().get(0).step().stepId().value()).isEqualTo("a@0");

        RecordedExecutionNodeV2 branch1 = wrapper.children().get(1);
        assertThat(branch1.step().stepId().value()).isEqualTo("par@1");
        assertThat(branch1.children().get(0).step().stepId().value()).isEqualTo("b@1");

        WorkflowRecordingV2 decoded = codec.decode(codec.encode(recording));
        assertThat(decoded).isEqualTo(recording);
    }

    // --- REC2-PAR-002: the last of two branches failing is captured faithfully -------------

    @Test
    void capturesTheLastOfTwoBranchesFailing() {
        WorkflowVariable<String> outA = WorkflowVariable.publicValue("outA", String.class);
        IWorkflowStep failingB =
                WorkflowSteps.action(
                        "b",
                        new ParallelSafeActionFactory<String>(
                                variables ->
                                        new FakePreparedAction<>(
                                                ActionResults.failure(
                                                        ActionFailureType.TARGET_NOT_FOUND,
                                                        "not found"))));
        Workflow workflow =
                Workflow.builder("wf-par-fail")
                        .step(
                                WorkflowSteps.parallel(
                                        "par",
                                        List.of(
                                                List.of(safeAction("a", outA).build()),
                                                List.of(failingB))))
                        .build();
        WorkflowExecutionPlan plan = WorkflowPlanner.plan(workflow);
        WorkflowExecution execution = engine.executeWithTree(workflow, WorkflowInputs.empty());
        assertThat(execution.result().status()).isEqualTo(WorkflowStatus.FAILED);

        WorkflowRecordingV2 recording =
                recorder.record(
                        new RecordingId("rec-par-fail"),
                        Instant.parse("2026-01-01T00:00:00Z"),
                        plan,
                        execution);

        RecordedExecutionNodeV2 wrapper = recording.nodes().get(0);
        assertThat(wrapper.children()).hasSize(2);
        assertThat(wrapper.children().get(0).children()).hasSize(1); // branch 0 kept
        RecordedExecutionNodeV2 branch1 = wrapper.children().get(1);
        // The branch wrapper's own status is SUCCEEDED (it was launched) - the branch's real
        // failure is carried by its own child, exactly like a loop iteration's body failure.
        assertThat(branch1.step().status()).isEqualTo(WorkflowStepStatus.SUCCEEDED);
        assertThat(branch1.children()).hasSize(1);
        assertThat(branch1.children().get(0).step().status()).isEqualTo(WorkflowStepStatus.FAILED);

        WorkflowRecordingV2 decoded = codec.decode(codec.encode(recording));
        assertThat(decoded).isEqualTo(recording);
    }

    // --- REC2-PAR-002B: a branch after the failing one is captured as NOT_RUN --------------

    @Test
    void capturesALaterBranchAsNotRunAfterAnEarlierFailure() {
        WorkflowVariable<String> outA = WorkflowVariable.publicValue("outA", String.class);
        WorkflowVariable<String> outC = WorkflowVariable.publicValue("outC", String.class);
        IWorkflowStep failingB =
                WorkflowSteps.action(
                        "b",
                        new ParallelSafeActionFactory<String>(
                                variables ->
                                        new FakePreparedAction<>(
                                                ActionResults.failure(
                                                        ActionFailureType.TARGET_NOT_FOUND,
                                                        "not found"))));
        Workflow workflow =
                Workflow.builder("wf-par-fail-3")
                        .step(
                                WorkflowSteps.parallel(
                                        "par",
                                        List.of(
                                                List.of(safeAction("a", outA).build()),
                                                List.of(failingB),
                                                List.of(safeAction("c", outC).build()))))
                        .build();
        WorkflowExecutionPlan plan = WorkflowPlanner.plan(workflow);
        WorkflowExecution execution = engine.executeWithTree(workflow, WorkflowInputs.empty());
        assertThat(execution.result().status()).isEqualTo(WorkflowStatus.FAILED);

        WorkflowRecordingV2 recording =
                recorder.record(
                        new RecordingId("rec-par-fail-3"),
                        Instant.parse("2026-01-01T00:00:00Z"),
                        plan,
                        execution);

        RecordedExecutionNodeV2 wrapper = recording.nodes().get(0);
        assertThat(wrapper.children()).hasSize(3);
        assertThat(wrapper.children().get(0).step().status())
                .isEqualTo(WorkflowStepStatus.SUCCEEDED); // branch 0 kept
        assertThat(wrapper.children().get(1).children()).hasSize(1); // branch 1: the failure
        RecordedExecutionNodeV2 branch2 = wrapper.children().get(2);
        assertThat(branch2.step().status()).isEqualTo(WorkflowStepStatus.NOT_RUN);
        assertThat(branch2.children()).isEmpty();

        WorkflowRecordingV2 decoded = codec.decode(codec.encode(recording));
        assertThat(decoded).isEqualTo(recording);
    }

    // --- REC2-PAR-003: branch count mismatch against the recorded plan is rejected --------

    @Test
    void branchCountMismatchAgainstPlanRejected() {
        WorkflowExecutionPlan plan = RecordingV2Fixtures.parallelPlan("wf", "par", "a", "b");
        RecordedExecutionNodeV2 onlyOneBranch =
                new RecordedExecutionNodeV2(
                        RecordingV2Fixtures.parallelStep("par"),
                        Optional.empty(),
                        List.of(
                                new RecordedExecutionNodeV2(
                                        RecordingV2Fixtures.parallelBranchStep("par@0"),
                                        Optional.empty(),
                                        List.of(
                                                RecordingV2Fixtures.leaf(
                                                        RecordingV2Fixtures.succeededActionStep(
                                                                "a@0", Optional.empty()))))));

        assertThatThrownBy(
                        () ->
                                RecordingV2Fixtures.recordingWith(
                                        "wf",
                                        plan,
                                        WorkflowStatus.COMPLETED,
                                        List.of(onlyOneBranch),
                                        Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // --- REC2-PAR-004: a PARALLEL_BRANCH with a bad step ID qualification is rejected ------

    @Test
    void malformedBranchStepIdRejected() {
        WorkflowExecutionPlan plan = RecordingV2Fixtures.parallelPlan("wf", "par", "a", "b");
        RecordedExecutionNodeV2 badlyQualified =
                new RecordedExecutionNodeV2(
                        RecordingV2Fixtures.parallelStep("par"),
                        Optional.empty(),
                        List.of(
                                new RecordedExecutionNodeV2(
                                        RecordingV2Fixtures.parallelBranchStep(
                                                "par@wrong"), // should be par@0
                                        Optional.empty(),
                                        List.of(
                                                RecordingV2Fixtures.leaf(
                                                        RecordingV2Fixtures.succeededActionStep(
                                                                "a@0", Optional.empty())))),
                                new RecordedExecutionNodeV2(
                                        RecordingV2Fixtures.parallelBranchStep("par@1"),
                                        Optional.empty(),
                                        List.of(
                                                RecordingV2Fixtures.leaf(
                                                        RecordingV2Fixtures.succeededActionStep(
                                                                "b@1", Optional.empty()))))));

        assertThatThrownBy(
                        () ->
                                RecordingV2Fixtures.recordingWith(
                                        "wf",
                                        plan,
                                        WorkflowStatus.COMPLETED,
                                        List.of(badlyQualified),
                                        Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // --- REC2-PAR-005: a PARALLEL node carrying a branch selection is rejected -------------

    @Test
    void parallelNodeWithBranchSelectionRejected() {
        WorkflowExecutionPlan plan = RecordingV2Fixtures.parallelPlan("wf", "par", "a", "b");
        assertThatThrownBy(
                        () ->
                                new RecordedExecutionNodeV2(
                                        RecordingV2Fixtures.parallelStep("par"),
                                        Optional.of(WorkflowBranchSelection.THEN),
                                        List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // --- REC2-PAR-006: a SUCCEEDED PARALLEL_BRANCH with zero children is rejected ----------

    @Test
    void succeededBranchWithZeroChildrenRejected() {
        WorkflowExecutionPlan plan = RecordingV2Fixtures.parallelPlan("wf", "par", "a", "b");
        RecordedExecutionNodeV2 emptyBranch =
                new RecordedExecutionNodeV2(
                        RecordingV2Fixtures.parallelStep("par"),
                        Optional.empty(),
                        List.of(
                                RecordingV2Fixtures.leaf(
                                        RecordingV2Fixtures.parallelBranchStep("par@0")),
                                new RecordedExecutionNodeV2(
                                        RecordingV2Fixtures.parallelBranchStep("par@1"),
                                        Optional.empty(),
                                        List.of(
                                                RecordingV2Fixtures.leaf(
                                                        RecordingV2Fixtures.succeededActionStep(
                                                                "b@1", Optional.empty()))))));

        assertThatThrownBy(
                        () ->
                                RecordingV2Fixtures.recordingWith(
                                        "wf",
                                        plan,
                                        WorkflowStatus.COMPLETED,
                                        List.of(emptyBranch),
                                        Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // --- REC2-PAR-007: a non-SUCCEEDED PARALLEL node carrying children is rejected --------

    @Test
    void skippedParallelNodeWithChildrenRejected() {
        WorkflowExecutionPlan plan = RecordingV2Fixtures.parallelPlan("wf", "par", "a", "b");
        RecordedExecutionNodeV2 skippedButWithChildren =
                new RecordedExecutionNodeV2(
                        RecordingV2Fixtures.skippedParallelStep("par", "guard"),
                        Optional.empty(),
                        List.of(
                                new RecordedExecutionNodeV2(
                                        RecordingV2Fixtures.parallelBranchStep("par@0"),
                                        Optional.empty(),
                                        List.of(
                                                RecordingV2Fixtures.leaf(
                                                        RecordingV2Fixtures.succeededActionStep(
                                                                "a@0", Optional.empty()))))));

        assertThatThrownBy(
                        () ->
                                RecordingV2Fixtures.recordingWith(
                                        "wf",
                                        plan,
                                        WorkflowStatus.COMPLETED,
                                        List.of(skippedButWithChildren),
                                        Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // --- REC2-PAR-008: a NOT_RUN PARALLEL_BRANCH carrying children is rejected -------------

    @Test
    void notRunBranchWithChildrenRejected() {
        WorkflowExecutionPlan plan = RecordingV2Fixtures.parallelPlan("wf", "par", "a", "b");
        RecordedExecutionNodeV2 notRunWithChildren =
                new RecordedExecutionNodeV2(
                        RecordingV2Fixtures.parallelStep("par"),
                        Optional.empty(),
                        List.of(
                                RecordingV2Fixtures.leaf(
                                        RecordingV2Fixtures.parallelBranchStep("par@0")),
                                new RecordedExecutionNodeV2(
                                        RecordingV2Fixtures.notRunParallelBranchStep("par@1"),
                                        Optional.empty(),
                                        List.of(
                                                RecordingV2Fixtures.leaf(
                                                        RecordingV2Fixtures.succeededActionStep(
                                                                "b@1", Optional.empty()))))));

        assertThatThrownBy(
                        () ->
                                RecordingV2Fixtures.recordingWith(
                                        "wf",
                                        plan,
                                        WorkflowStatus.COMPLETED,
                                        List.of(notRunWithChildren),
                                        Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
