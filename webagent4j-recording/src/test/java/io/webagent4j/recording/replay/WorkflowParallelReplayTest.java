package io.webagent4j.recording.replay;

import static org.assertj.core.api.Assertions.assertThat;

import io.webagent4j.recording.JsonWorkflowRecordingV2Codec;
import io.webagent4j.recording.RecordingId;
import io.webagent4j.recording.WorkflowRecorderV2;
import io.webagent4j.recording.WorkflowRecordingV2;
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
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * RPL-PAR coverage: Deterministic Replay of a real {@code PARALLEL} {@code WorkflowExecution}'s
 * recorded trace, including a loop nested inside a branch whose {@code maxIterations} bound must
 * still be resolved structurally against the live workflow. See {@code docs/recording.md#parallel}.
 *
 * <p>Since a Workflow {@code ACTION} step is never permitted inside a {@code PARALLEL} branch (see
 * {@code io.webagent4j.workflow.WorkflowParallelActionSafetyTest}), every branch here is built from
 * an always-true {@link IWorkflowCondition} driving an {@code ifThen} whose body is a deterministic
 * {@code ASSIGN} - the allowed-step-type equivalent of the former test-only parallel-safe ACTION
 * fixture.
 */
class WorkflowParallelReplayTest {

    private final WorkflowEngine engine = new WorkflowEngine();
    private final WorkflowRecorderV2 recorder = new WorkflowRecorderV2();
    private final JsonWorkflowRecordingV2Codec codec = new JsonWorkflowRecordingV2Codec();

    private static IWorkflowCondition alwaysTrue() {
        return new IWorkflowCondition() {
            @Override
            public boolean evaluate(IWorkflowVariables variables) {
                return true;
            }

            @Override
            public String describe() {
                return "true";
            }

            @Override
            public Set<WorkflowVariable<?>> referencedVariables() {
                return Set.of();
            }
        };
    }

    private static IWorkflowStep publish(String id, WorkflowVariable<String> output) {
        return WorkflowSteps.ifThen(
                id,
                alwaysTrue(),
                List.of(WorkflowSteps.assign(id + "-assign", output, id + "-value")));
    }

    private static IWorkflowStep noopStep(String id) {
        return WorkflowSteps.ifThen(
                id,
                alwaysTrue(),
                List.of(
                        WorkflowSteps.assign(
                                id + "-assign",
                                WorkflowVariable.publicValue(id + "NoopVar", String.class),
                                "x")));
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
                                                List.of(publish("a", outA)),
                                                List.of(publish("b", outB)))))
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
        // PARALLEL + BRANCH0 + a@0 + a-assign@0 + BRANCH1 + b@1 + b-assign@1
        assertThat(replayed.steps()).hasSize(7);
        assertThat(replayed.steps().get(0).step().stepType()).isEqualTo(WorkflowStepType.PARALLEL);
        assertThat(replayed.steps().get(1).step().stepType())
                .isEqualTo(WorkflowStepType.PARALLEL_BRANCH);
        assertThat(replayed.steps().get(2).step().stepId().value()).isEqualTo("a@0");
        assertThat(replayed.steps().get(5).step().stepId().value()).isEqualTo("b@1");

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
                                                List.of(publish("a", outA)),
                                                List.of(publish("b", outB)))))
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
                                                List.of(publish("a", outA)),
                                                List.of(publish("b", outB)),
                                                List.of(publish("c", outC)))))
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
                                                                List.of(noopStep("body")))),
                                                List.of(publish("b", outB)))))
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
