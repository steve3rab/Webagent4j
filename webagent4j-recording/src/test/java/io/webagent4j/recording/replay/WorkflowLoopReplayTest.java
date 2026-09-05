package io.webagent4j.recording.replay;

import static org.assertj.core.api.Assertions.assertThat;

import io.webagent4j.action.ActionExecutionMode;
import io.webagent4j.action.ActionId;
import io.webagent4j.action.ActionResult;
import io.webagent4j.action.ActionStatus;
import io.webagent4j.action.ActionType;
import io.webagent4j.action.IPreparedAction;
import io.webagent4j.recording.RecordedAction;
import io.webagent4j.recording.RecordedCondition;
import io.webagent4j.recording.RecordedExecutionNodeV2;
import io.webagent4j.recording.RecordedWorkflowStepV2;
import io.webagent4j.recording.RecordingId;
import io.webagent4j.recording.RecordingSchemaVersionV2;
import io.webagent4j.recording.WorkflowRecorderV2;
import io.webagent4j.recording.WorkflowRecordingV2;
import io.webagent4j.workflow.IWorkflowCondition;
import io.webagent4j.workflow.IWorkflowVariables;
import io.webagent4j.workflow.Workflow;
import io.webagent4j.workflow.WorkflowBranchSelection;
import io.webagent4j.workflow.WorkflowEngine;
import io.webagent4j.workflow.WorkflowExecution;
import io.webagent4j.workflow.WorkflowExecutionPlan;
import io.webagent4j.workflow.WorkflowInputs;
import io.webagent4j.workflow.WorkflowPlanner;
import io.webagent4j.workflow.WorkflowStatus;
import io.webagent4j.workflow.WorkflowStepId;
import io.webagent4j.workflow.WorkflowStepStatus;
import io.webagent4j.workflow.WorkflowStepType;
import io.webagent4j.workflow.WorkflowSteps;
import io.webagent4j.workflow.WorkflowVariable;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * RPL-LOOP coverage: {@link ReplayValidator}/{@link WorkflowReplayer} for a recorded bounded loop -
 * exact iteration count/order reproduction, no condition re-evaluation, and rejection of a hostile
 * recording claiming more iterations than the live workflow's declared {@code maxIterations}
 * authorizes. See {@code docs/recording.md#bounded-loops}.
 */
class WorkflowLoopReplayTest {

    private final WorkflowEngine engine = new WorkflowEngine();
    private final WorkflowRecorderV2 recorder = new WorkflowRecorderV2();

    private static final class CountingUntilFalseCondition implements IWorkflowCondition {
        private final int trueCount;
        private final AtomicInteger evaluations;

        CountingUntilFalseCondition(int trueCount, AtomicInteger evaluations) {
            this.trueCount = trueCount;
            this.evaluations = evaluations;
        }

        @Override
        public boolean evaluate(IWorkflowVariables variables) {
            return evaluations.getAndIncrement() < trueCount;
        }

        @Override
        public String describe() {
            return "untilFalse(" + trueCount + ")";
        }

        @Override
        public Set<WorkflowVariable<?>> referencedVariables() {
            return Set.of();
        }
    }

    /**
     * A minimal, local {@code IPreparedAction<String>} fake that always succeeds - this package
     * cannot reuse {@code io.webagent4j.recording}'s own package-private test helpers, mirroring
     * {@code WorkflowReplayerTest}'s own identical local fake for the same reason.
     */
    private static final class SentinelPreparedAction implements IPreparedAction<String> {

        private final String value;

        SentinelPreparedAction(String value) {
            this.value = value;
        }

        @Override
        public IPreparedAction<String> precondition(
                java.util.function.Predicate<io.webagent4j.dom.IElement> predicate) {
            return this;
        }

        @Override
        public IPreparedAction<String> require(
                io.webagent4j.verification.IVerification verification) {
            return this;
        }

        @Override
        public IPreparedAction<String> expect(
                io.webagent4j.verification.IVerification verification) {
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
        public IPreparedAction<String> retry(io.webagent4j.common.RetryPolicy retryPolicy) {
            return this;
        }

        @Override
        public IPreparedAction<String> captureObservations(
                io.webagent4j.action.ObservationCapturePolicy policy) {
            return this;
        }

        @Override
        public ActionResult<String> execute() {
            return new ActionResult<>(
                    true,
                    value,
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
        public io.webagent4j.action.IActionPlan<String> plan() {
            throw new UnsupportedOperationException("not used by this test");
        }
    }

    private static Workflow loopWorkflow(String id, int trueCount, int maxIterations) {
        return Workflow.builder(id)
                .step(
                        WorkflowSteps.loop(
                                "loop",
                                new CountingUntilFalseCondition(trueCount, new AtomicInteger()),
                                maxIterations,
                                List.of(
                                        WorkflowSteps.action(
                                                "body", vars -> new SentinelPreparedAction("ok")))))
                .build();
    }

    @Test
    void replaysAGenuineLoopRecordingWithExactIterationCountAndOrder() {
        Workflow workflow = loopWorkflow("wf-replay-loop-3", 3, 5);
        WorkflowExecutionPlan plan = WorkflowPlanner.plan(workflow);
        WorkflowExecution execution = engine.executeWithTree(workflow, WorkflowInputs.empty());
        assertThat(execution.result().completed()).isTrue();
        WorkflowRecordingV2 recording =
                recorder.record(new RecordingId("rec"), Instant.now(), plan, execution);

        Optional<ReplayValidationFailure> validation =
                ReplayValidator.validate(recording, workflow);
        assertThat(validation).isEmpty();

        IReplayOutcome outcome = WorkflowReplayer.replay(recording, workflow);
        assertThat(outcome).isInstanceOf(IReplayOutcome.Replayed.class);
        ReplayedWorkflow replayed = ((IReplayOutcome.Replayed) outcome).workflow();
        // wrapper + (3 * [iteration-decision, body]) + final false decision = 1 + 6 + 1 = 8
        assertThat(replayed.steps()).hasSize(8);
        assertThat(replayed.steps().get(0).step().stepType()).isEqualTo(WorkflowStepType.LOOP);
        long loopIterationCount =
                replayed.steps().stream()
                        .filter(s -> s.step().stepType() == WorkflowStepType.LOOP_ITERATION)
                        .count();
        assertThat(loopIterationCount).isEqualTo(4); // 3 true + 1 false
        long bodyCount =
                replayed.steps().stream()
                        .filter(s -> s.step().stepId().value().startsWith("body"))
                        .count();
        assertThat(bodyCount).isEqualTo(3);
    }

    @Test
    void rejectsARecordingClaimingMoreIterationsThanTheLiveWorkflowAuthorizes() {
        Workflow workflow = loopWorkflow("wf-hostile-loop", 100, 5);
        WorkflowExecutionPlan plan = WorkflowPlanner.plan(workflow);

        // Hand-fabricate 6 authorized ("THEN") iterations plus a final false stop - one more
        // successful iteration than the live workflow's maxIterations=5 permits - structurally
        // self-consistent (RecordingV2PlanTreeValidator accepts it, since it never checks the
        // bound), but never producible by a real, bounded execution of `workflow`.
        List<RecordedExecutionNodeV2> iterations = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            RecordedWorkflowStepV2 decision =
                    new RecordedWorkflowStepV2(
                            new WorkflowStepId("loop#" + i),
                            WorkflowStepType.LOOP_ITERATION,
                            WorkflowStepStatus.SUCCEEDED,
                            Optional.of(new RecordedCondition(true, "d")),
                            Optional.empty(),
                            Optional.empty(),
                            Optional.empty());
            RecordedWorkflowStepV2 bodyStep =
                    new RecordedWorkflowStepV2(
                            new WorkflowStepId("body#" + i),
                            WorkflowStepType.ACTION,
                            WorkflowStepStatus.SUCCEEDED,
                            Optional.empty(),
                            Optional.empty(),
                            Optional.empty(),
                            Optional.of(
                                    new RecordedAction(
                                            ActionId.create(),
                                            ActionType.CLICK,
                                            ActionStatus.SUCCESS,
                                            ActionExecutionMode.REAL)));
            iterations.add(
                    new RecordedExecutionNodeV2(
                            decision,
                            Optional.of(WorkflowBranchSelection.THEN),
                            List.of(
                                    new RecordedExecutionNodeV2(
                                            bodyStep, Optional.empty(), List.of()))));
        }
        RecordedWorkflowStepV2 finalStop =
                new RecordedWorkflowStepV2(
                        new WorkflowStepId("loop#6"),
                        WorkflowStepType.LOOP_ITERATION,
                        WorkflowStepStatus.SUCCEEDED,
                        Optional.of(new RecordedCondition(false, "d")),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty());
        iterations.add(
                new RecordedExecutionNodeV2(
                        finalStop, Optional.of(WorkflowBranchSelection.NONE), List.of()));

        RecordedWorkflowStepV2 wrapperStep =
                new RecordedWorkflowStepV2(
                        new WorkflowStepId("loop"),
                        WorkflowStepType.LOOP,
                        WorkflowStepStatus.SUCCEEDED,
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty());
        RecordedExecutionNodeV2 wrapper =
                new RecordedExecutionNodeV2(wrapperStep, Optional.empty(), List.copyOf(iterations));

        WorkflowRecordingV2 hostileRecording =
                new WorkflowRecordingV2(
                        RecordingSchemaVersionV2.V2,
                        new RecordingId("hostile"),
                        Instant.now(),
                        plan.workflowId(),
                        WorkflowStatus.COMPLETED,
                        plan,
                        List.of(wrapper),
                        Optional.empty());

        Optional<ReplayValidationFailure> validation =
                ReplayValidator.validate(hostileRecording, workflow);

        assertThat(validation).isPresent();
        assertThat(validation.get().type())
                .isEqualTo(ReplayFailureType.LOOP_ITERATION_COUNT_EXCEEDS_BOUND);

        IReplayOutcome outcome = WorkflowReplayer.replay(hostileRecording, workflow);
        assertThat(outcome).isInstanceOf(IReplayOutcome.Rejected.class);
    }

    @Test
    void replayIsDeterministicAcrossRepeatsForALoop() {
        Workflow workflow = loopWorkflow("wf-replay-deterministic", 2, 5);
        WorkflowExecutionPlan plan = WorkflowPlanner.plan(workflow);
        WorkflowExecution execution = engine.executeWithTree(workflow, WorkflowInputs.empty());
        WorkflowRecordingV2 recording =
                recorder.record(new RecordingId("rec"), Instant.now(), plan, execution);

        IReplayOutcome first = WorkflowReplayer.replay(recording, workflow);
        IReplayOutcome second = WorkflowReplayer.replay(recording, workflow);
        assertThat(first).isEqualTo(second);
    }
}
