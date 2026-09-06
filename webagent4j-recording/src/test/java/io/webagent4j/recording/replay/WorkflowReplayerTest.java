package io.webagent4j.recording.replay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.webagent4j.recording.RecordedWorkflowStepV2;
import io.webagent4j.recording.RecordingId;
import io.webagent4j.recording.WorkflowRecorderV2;
import io.webagent4j.recording.WorkflowRecordingV2;
import io.webagent4j.workflow.Workflow;
import io.webagent4j.workflow.WorkflowBranchSelection;
import io.webagent4j.workflow.WorkflowConditions;
import io.webagent4j.workflow.WorkflowEngine;
import io.webagent4j.workflow.WorkflowExecution;
import io.webagent4j.workflow.WorkflowExecutionPlan;
import io.webagent4j.workflow.WorkflowInputs;
import io.webagent4j.workflow.WorkflowPlanner;
import io.webagent4j.workflow.WorkflowStepStatus;
import io.webagent4j.workflow.WorkflowStepType;
import io.webagent4j.workflow.WorkflowSteps;
import io.webagent4j.workflow.WorkflowVariable;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * RPL-REPLAY coverage for {@link WorkflowReplayer}: replaying a real, recorded decision trace
 * against a live workflow, using genuine {@link WorkflowEngine} executions.
 */
class WorkflowReplayerTest {

    private final WorkflowEngine engine = new WorkflowEngine();
    private final WorkflowRecorderV2 recorder = new WorkflowRecorderV2();

    @Test
    void rplReplay001LinearWorkflowReplaysEveryStepInOrder() {
        WorkflowVariable<String> firstOutput =
                WorkflowVariable.publicValue("firstOut", String.class);
        WorkflowVariable<String> assignOutput =
                WorkflowVariable.publicValue("assignOut", String.class);
        Workflow workflow =
                Workflow.builder("wf-replay-linear")
                        .step(WorkflowSteps.assign("s1", firstOutput, "first"))
                        .step(WorkflowSteps.assign("s2", assignOutput, "literal"))
                        .build();
        WorkflowExecutionPlan plan = WorkflowPlanner.plan(workflow);
        WorkflowExecution execution = engine.executeWithTree(workflow, WorkflowInputs.empty());
        WorkflowRecordingV2 recording =
                recorder.record(new RecordingId("rec"), Instant.now(), plan, execution);

        IReplayOutcome outcome = WorkflowReplayer.replay(recording, workflow);

        assertThat(outcome).isInstanceOf(IReplayOutcome.Replayed.class);
        ReplayedWorkflow replayed = ((IReplayOutcome.Replayed) outcome).workflow();
        assertThat(replayed.workflowId()).isEqualTo(workflow.id());
        assertThat(replayed.steps()).hasSize(2);
        assertThat(replayed.steps().get(0).step().stepId().value()).isEqualTo("s1");
        assertThat(replayed.steps().get(0).branchSelection()).isEmpty();
        assertThat(replayed.steps().get(1).step().stepId().value()).isEqualTo("s2");
    }

    @Test
    void rplReplay002SelectedThenBranchIsReplayedAndElseIsAbsent() {
        WorkflowVariable<Boolean> flag = WorkflowVariable.publicValue("flag", Boolean.class);
        Workflow workflow =
                Workflow.builder("wf-replay-branch")
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
        WorkflowRecordingV2 recording =
                recorder.record(new RecordingId("rec-branch"), Instant.now(), plan, execution);

        IReplayOutcome outcome = WorkflowReplayer.replay(recording, workflow);

        ReplayedWorkflow replayed = ((IReplayOutcome.Replayed) outcome).workflow();
        assertThat(replayed.steps()).hasSize(2);
        ReplayedStep conditional = replayed.steps().get(0);
        assertThat(conditional.step().stepType()).isEqualTo(WorkflowStepType.CONDITIONAL);
        assertThat(conditional.branchSelection()).contains(WorkflowBranchSelection.THEN);
        assertThat(replayed.steps().get(1).step().stepId().value()).isEqualTo("then");
        assertThat(replayed.steps().stream().map(s -> s.step().stepId().value()))
                .doesNotContain("else");
    }

    @Test
    void rplReplay003NestedBranchingFlattensInExecutionOrder() {
        WorkflowVariable<Boolean> a = WorkflowVariable.publicValue("a", Boolean.class);
        WorkflowVariable<Boolean> b = WorkflowVariable.publicValue("b", Boolean.class);
        Workflow workflow =
                Workflow.builder("wf-replay-nested")
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
                recorder.record(new RecordingId("rec-nested"), Instant.now(), plan, execution);

        IReplayOutcome outcome = WorkflowReplayer.replay(recording, workflow);

        ReplayedWorkflow replayed = ((IReplayOutcome.Replayed) outcome).workflow();
        assertThat(replayed.steps().stream().map(s -> s.step().stepId().value()))
                .containsExactly("outer", "inner", "y");
    }

    @Test
    void rplReplay004IncompatibleWorkflowIsRejectedWithoutAnyReplay() {
        Workflow original =
                Workflow.builder("wf-replay-mismatch")
                        .step(
                                WorkflowSteps.assign(
                                        "s1",
                                        WorkflowVariable.publicValue("v1", String.class),
                                        "x"))
                        .build();
        WorkflowExecutionPlan plan = WorkflowPlanner.plan(original);
        WorkflowExecution execution = engine.executeWithTree(original, WorkflowInputs.empty());
        WorkflowRecordingV2 recording =
                recorder.record(new RecordingId("rec-mismatch"), Instant.now(), plan, execution);

        Workflow changed =
                Workflow.builder("wf-replay-mismatch")
                        .step(
                                WorkflowSteps.assign(
                                        "s1",
                                        WorkflowVariable.publicValue("v1", String.class),
                                        "x"))
                        .step(
                                WorkflowSteps.assign(
                                        "s2",
                                        WorkflowVariable.publicValue("v2", String.class),
                                        "y"))
                        .build();

        IReplayOutcome outcome = WorkflowReplayer.replay(recording, changed);

        assertThat(outcome).isInstanceOf(IReplayOutcome.Rejected.class);
        assertThat(((IReplayOutcome.Rejected) outcome).failure().type())
                .isEqualTo(ReplayFailureType.INCOMPATIBLE_WORKFLOW);
    }

    @Test
    void rplReplay005FailedRecordingIsRejectedWithoutAnyReplay() {
        Workflow workflow =
                Workflow.builder("wf-replay-failed")
                        .step(
                                WorkflowSteps.action(
                                        "s1",
                                        vars -> {
                                            throw new RuntimeException("boom");
                                        }))
                        .build();
        WorkflowExecutionPlan plan = WorkflowPlanner.plan(workflow);
        WorkflowExecution execution = engine.executeWithTree(workflow, WorkflowInputs.empty());
        WorkflowRecordingV2 recording =
                recorder.record(new RecordingId("rec-failed"), Instant.now(), plan, execution);

        IReplayOutcome outcome = WorkflowReplayer.replay(recording, workflow);

        assertThat(outcome).isInstanceOf(IReplayOutcome.Rejected.class);
        assertThat(((IReplayOutcome.Rejected) outcome).failure().type())
                .isEqualTo(ReplayFailureType.UNSUPPORTED_STATUS);
    }

    @Test
    void rplReplay006ReplayIsDeterministicAcrossRepeatedCalls() {
        WorkflowVariable<Boolean> flag = WorkflowVariable.publicValue("flag", Boolean.class);
        Workflow workflow =
                Workflow.builder("wf-replay-deterministic")
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
        WorkflowExecutionPlan plan = WorkflowPlanner.plan(workflow);
        WorkflowExecution execution =
                engine.executeWithTree(workflow, WorkflowInputs.builder().put(flag, true).build());
        WorkflowRecordingV2 recording =
                recorder.record(new RecordingId("rec-det"), Instant.now(), plan, execution);

        IReplayOutcome first = WorkflowReplayer.replay(recording, workflow);
        IReplayOutcome second = WorkflowReplayer.replay(recording, workflow);

        assertThat(first).isEqualTo(second);
    }

    @Test
    void rplReplay007SecretOutputClassificationSurvivesReplayWithoutAValue() {
        String sentinel = "WA4J_REPLAY_SAFE_SENTINEL_40218";
        WorkflowVariable<String> secretOut = WorkflowVariable.secret("secretOut");
        Workflow workflow =
                Workflow.builder("wf-replay-secret")
                        .step(
                                WorkflowSteps.action(
                                        "s1",
                                        vars -> new SentinelPreparedAction(sentinel),
                                        secretOut))
                        .build();
        WorkflowExecutionPlan plan = WorkflowPlanner.plan(workflow);
        WorkflowExecution execution = engine.executeWithTree(workflow, WorkflowInputs.empty());
        WorkflowRecordingV2 recording =
                recorder.record(new RecordingId("rec-secret"), Instant.now(), plan, execution);

        IReplayOutcome outcome = WorkflowReplayer.replay(recording, workflow);

        ReplayedWorkflow replayed = ((IReplayOutcome.Replayed) outcome).workflow();
        RecordedWorkflowStepV2 step = replayed.steps().get(0).step();
        assertThat(step.output().orElseThrow().secret()).isTrue();
        assertThat(replayed.toString()).doesNotContain(sentinel);
    }

    @Test
    void rplReplay008NullArgumentsAreRejected() {
        Workflow workflow =
                Workflow.builder("wf-replay-null")
                        .step(
                                WorkflowSteps.assign(
                                        "s1",
                                        WorkflowVariable.publicValue("v1", String.class),
                                        "x"))
                        .build();

        assertThatThrownBy(() -> WorkflowReplayer.replay(null, workflow))
                .isInstanceOf(NullPointerException.class);

        WorkflowExecutionPlan plan = WorkflowPlanner.plan(workflow);
        WorkflowExecution execution = engine.executeWithTree(workflow, WorkflowInputs.empty());
        WorkflowRecordingV2 recording =
                recorder.record(new RecordingId("rec-null"), Instant.now(), plan, execution);
        assertThatThrownBy(() -> WorkflowReplayer.replay(recording, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void rplReplay009ReplayedStepRejectsBranchSelectionOnNonConditionalStep() {
        RecordedWorkflowStepV2 assignStep =
                new RecordedWorkflowStepV2(
                        new io.webagent4j.workflow.WorkflowStepId("s1"),
                        WorkflowStepType.ASSIGN,
                        WorkflowStepStatus.SUCCEEDED,
                        java.util.Optional.empty(),
                        java.util.Optional.of(
                                new io.webagent4j.workflow.WorkflowPlanOutput(
                                        "out", "String", false)),
                        java.util.Optional.empty(),
                        java.util.Optional.empty());

        assertThatThrownBy(
                        () ->
                                new ReplayedStep(
                                        assignStep,
                                        java.util.Optional.of(WorkflowBranchSelection.THEN)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * A minimal, local {@code IPreparedAction<String>} fake that always succeeds with a fixed value
     * - this package cannot reuse {@code io.webagent4j.recording}'s own package-private {@code
     * FakePreparedAction}/{@code ActionResults} test helpers, since {@code
     * io.webagent4j.recording.replay} is a different package.
     */
    private static final class SentinelPreparedAction
            implements io.webagent4j.action.IPreparedAction<String> {

        private final String value;

        SentinelPreparedAction(String value) {
            this.value = value;
        }

        @Override
        public io.webagent4j.action.IPreparedAction<String> precondition(
                java.util.function.Predicate<io.webagent4j.dom.IElement> predicate) {
            return this;
        }

        @Override
        public io.webagent4j.action.IPreparedAction<String> require(
                io.webagent4j.verification.IVerification verification) {
            return this;
        }

        @Override
        public io.webagent4j.action.IPreparedAction<String> expect(
                io.webagent4j.verification.IVerification verification) {
            return this;
        }

        @Override
        public io.webagent4j.action.IPreparedAction<String> expectUrlContains(
                String expectedFragment) {
            return this;
        }

        @Override
        public io.webagent4j.action.IPreparedAction<String> timeout(java.time.Duration timeout) {
            return this;
        }

        @Override
        public io.webagent4j.action.IPreparedAction<String> retry(
                io.webagent4j.common.RetryPolicy retryPolicy) {
            return this;
        }

        @Override
        public io.webagent4j.action.IPreparedAction<String> captureObservations(
                io.webagent4j.action.ObservationCapturePolicy policy) {
            return this;
        }

        @Override
        public io.webagent4j.action.ActionResult<String> execute() {
            return new io.webagent4j.action.ActionResult<>(
                    true,
                    value,
                    java.time.Duration.ZERO,
                    List.of(),
                    java.util.Optional.empty(),
                    io.webagent4j.action.ActionExecutionMode.REAL);
        }

        @Override
        public io.webagent4j.action.IPreparedAction<String> dryRun() {
            return this;
        }

        @Override
        public io.webagent4j.action.IActionPlan<String> plan() {
            throw new UnsupportedOperationException("not used by this test");
        }
    }
}
