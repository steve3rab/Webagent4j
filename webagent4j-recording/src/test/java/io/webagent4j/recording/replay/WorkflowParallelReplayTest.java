package io.webagent4j.recording.replay;

import static org.assertj.core.api.Assertions.assertThat;

import io.webagent4j.action.ActionExecutionMode;
import io.webagent4j.action.ActionResult;
import io.webagent4j.action.IPreparedAction;
import io.webagent4j.recording.JsonWorkflowRecordingV2Codec;
import io.webagent4j.recording.RecordingId;
import io.webagent4j.recording.WorkflowRecorderV2;
import io.webagent4j.recording.WorkflowRecordingV2;
import io.webagent4j.workflow.IWorkflowActionFactory;
import io.webagent4j.workflow.IWorkflowCondition;
import io.webagent4j.workflow.IWorkflowStep;
import io.webagent4j.workflow.IWorkflowVariables;
import io.webagent4j.workflow.Workflow;
import io.webagent4j.workflow.WorkflowEngine;
import io.webagent4j.workflow.WorkflowExecution;
import io.webagent4j.workflow.WorkflowExecutionPlan;
import io.webagent4j.workflow.WorkflowInputs;
import io.webagent4j.workflow.WorkflowPlanner;
import io.webagent4j.workflow.WorkflowStepType;
import io.webagent4j.workflow.WorkflowSteps;
import io.webagent4j.workflow.WorkflowVariable;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * RPL-PAR coverage: Deterministic Replay of a real {@code PARALLEL} {@code WorkflowExecution}'s
 * recorded trace, including a loop nested inside a branch whose {@code maxIterations} bound must
 * still be resolved structurally against the live workflow. See {@code docs/recording.md#parallel}.
 */
class WorkflowParallelReplayTest {

    private final WorkflowEngine engine = new WorkflowEngine();
    private final WorkflowRecorderV2 recorder = new WorkflowRecorderV2();
    private final JsonWorkflowRecordingV2Codec codec = new JsonWorkflowRecordingV2Codec();

    /**
     * A minimal, local {@code IPreparedAction<String>} fake that always succeeds - this package
     * cannot reuse {@code io.webagent4j.recording}'s own package-private test helpers, mirroring
     * {@code WorkflowLoopReplayTest}'s own identical local fake for the same reason.
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

    /**
     * A local, named {@link IWorkflowActionFactory} declaring itself parallel-safe - a plain lambda
     * cannot override {@link IWorkflowActionFactory#isParallelSafe()}.
     */
    private static final class SafeFactory implements IWorkflowActionFactory<String> {
        private final String value;

        SafeFactory(String value) {
            this.value = value;
        }

        @Override
        public IPreparedAction<String> prepare(IWorkflowVariables variables) {
            return new SentinelPreparedAction(value);
        }

        @Override
        public boolean isParallelSafe() {
            return true;
        }
    }

    private static IWorkflowStep safeAction(String id, WorkflowVariable<String> output) {
        return WorkflowSteps.action(id, new SafeFactory(id + "-value"), output);
    }

    private static IWorkflowStep safeActionNoOutput(String id) {
        return WorkflowSteps.action(id, new SafeFactory(id + "-value"));
    }

    // --- RPL-PAR-001: a genuine two-branch parallel execution replays exactly -------------

    @Test
    void replaysATwoBranchParallelExecutionExactly() {
        WorkflowVariable<String> outA = WorkflowVariable.publicValue("outA", String.class);
        WorkflowVariable<String> outB = WorkflowVariable.publicValue("outB", String.class);
        Workflow workflow =
                Workflow.builder("wf-par-replay")
                        .step(
                                WorkflowSteps.parallel(
                                        "par",
                                        List.of(
                                                List.of(safeAction("a", outA)),
                                                List.of(safeAction("b", outB)))))
                        .build();
        WorkflowExecutionPlan plan = WorkflowPlanner.plan(workflow);
        WorkflowExecution execution = engine.executeWithTree(workflow, WorkflowInputs.empty());
        WorkflowRecordingV2 recording =
                recorder.record(
                        new RecordingId("rec-par-replay"),
                        Instant.parse("2026-01-01T00:00:00Z"),
                        plan,
                        execution);

        IReplayOutcome outcome = WorkflowReplayer.replay(recording, workflow);

        assertThat(outcome).isInstanceOf(IReplayOutcome.Replayed.class);
        ReplayedWorkflow replayed = ((IReplayOutcome.Replayed) outcome).workflow();
        assertThat(replayed.steps()).hasSize(5); // PARALLEL + BRANCH0 + a@0 + BRANCH1 + b@1
        assertThat(replayed.steps().get(0).step().stepType()).isEqualTo(WorkflowStepType.PARALLEL);
        assertThat(replayed.steps().get(1).step().stepType())
                .isEqualTo(WorkflowStepType.PARALLEL_BRANCH);
        assertThat(replayed.steps().get(2).step().stepId().value()).isEqualTo("a@0");
        assertThat(replayed.steps().get(4).step().stepId().value()).isEqualTo("b@1");

        // Also survives a JSON round-trip.
        WorkflowRecordingV2 decoded = codec.decode(codec.encode(recording));
        assertThat(WorkflowReplayer.replay(decoded, workflow)).isEqualTo(outcome);
    }

    // --- RPL-PAR-002: a structural mismatch (workflow changed) is rejected ----------------

    @Test
    void structuralMismatchAgainstLiveWorkflowRejected() {
        WorkflowVariable<String> outA = WorkflowVariable.publicValue("outA", String.class);
        WorkflowVariable<String> outB = WorkflowVariable.publicValue("outB", String.class);
        Workflow original =
                Workflow.builder("wf-par-mismatch")
                        .step(
                                WorkflowSteps.parallel(
                                        "par",
                                        List.of(
                                                List.of(safeAction("a", outA)),
                                                List.of(safeAction("b", outB)))))
                        .build();
        WorkflowExecutionPlan plan = WorkflowPlanner.plan(original);
        WorkflowExecution execution = engine.executeWithTree(original, WorkflowInputs.empty());
        WorkflowRecordingV2 recording =
                recorder.record(
                        new RecordingId("rec-par-mismatch"),
                        Instant.parse("2026-01-01T00:00:00Z"),
                        plan,
                        execution);

        // A live workflow with a third branch added - structurally different.
        WorkflowVariable<String> outC = WorkflowVariable.publicValue("outC", String.class);
        Workflow changed =
                Workflow.builder("wf-par-mismatch")
                        .step(
                                WorkflowSteps.parallel(
                                        "par",
                                        List.of(
                                                List.of(safeAction("a", outA)),
                                                List.of(safeAction("b", outB)),
                                                List.of(safeAction("c", outC)))))
                        .build();

        IReplayOutcome outcome = WorkflowReplayer.replay(recording, changed);

        assertThat(outcome).isInstanceOf(IReplayOutcome.Rejected.class);
        assertThat(((IReplayOutcome.Rejected) outcome).failure().type())
                .isEqualTo(ReplayFailureType.INCOMPATIBLE_WORKFLOW);
    }

    // --- RPL-PAR-003: a loop nested inside a parallel branch resolves its bound structurally

    @Test
    void loopNestedInsideParallelBranchResolvesBoundStructurally() {
        AtomicInteger evaluations = new AtomicInteger();
        IWorkflowCondition trueTwiceThenFalse =
                new IWorkflowCondition() {
                    @Override
                    public boolean evaluate(IWorkflowVariables variables) {
                        return evaluations.getAndIncrement() < 2;
                    }

                    @Override
                    public String describe() {
                        return "trueTwiceThenFalse";
                    }

                    @Override
                    public Set<WorkflowVariable<?>> referencedVariables() {
                        return Set.of();
                    }
                };
        WorkflowVariable<String> outB = WorkflowVariable.publicValue("outB", String.class);
        Workflow workflow =
                Workflow.builder("wf-par-loop")
                        .step(
                                WorkflowSteps.parallel(
                                        "par",
                                        List.of(
                                                List.of(
                                                        WorkflowSteps.loop(
                                                                "innerLoop",
                                                                trueTwiceThenFalse,
                                                                5,
                                                                List.of(
                                                                        safeActionNoOutput(
                                                                                "body")))),
                                                List.of(safeAction("b", outB)))))
                        .build();
        WorkflowExecutionPlan plan = WorkflowPlanner.plan(workflow);
        WorkflowExecution execution = engine.executeWithTree(workflow, WorkflowInputs.empty());
        assertThat(execution.result().completed()).isTrue();
        WorkflowRecordingV2 recording =
                recorder.record(
                        new RecordingId("rec-par-loop"),
                        Instant.parse("2026-01-01T00:00:00Z"),
                        plan,
                        execution);

        IReplayOutcome outcome = WorkflowReplayer.replay(recording, workflow);

        assertThat(outcome).isInstanceOf(IReplayOutcome.Replayed.class);
        ReplayedWorkflow replayed = ((IReplayOutcome.Replayed) outcome).workflow();
        assertThat(
                        replayed.steps().stream()
                                .anyMatch(s -> s.step().stepId().value().equals("body@0#0")))
                .isTrue();
    }
}
