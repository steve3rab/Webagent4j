package io.webagent4j.recording;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.webagent4j.workflow.IWorkflowCondition;
import io.webagent4j.workflow.IWorkflowVariables;
import io.webagent4j.workflow.Workflow;
import io.webagent4j.workflow.WorkflowBranchSelection;
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
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * REC2-LOOP coverage: capturing a real bounded-loop {@code WorkflowExecution} into {@link
 * WorkflowRecordingV2}, round-tripping it through {@link JsonWorkflowRecordingV2Codec}, and
 * rejecting hostile/malformed loop shapes at construction and decode time. See {@code
 * docs/recording.md#bounded-loops}.
 */
class WorkflowLoopRecordingV2Test {

    private final WorkflowEngine engine = new WorkflowEngine();
    private final WorkflowRecorderV2 recorder = new WorkflowRecorderV2();
    private final JsonWorkflowRecordingV2Codec codec = new JsonWorkflowRecordingV2Codec();

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

    @Test
    void capturesAndRoundTripsAThreeIterationLoop() {
        Workflow workflow =
                Workflow.builder("wf-loop")
                        .step(
                                WorkflowSteps.loop(
                                        "loop",
                                        new CountingUntilFalseCondition(3, new AtomicInteger()),
                                        5,
                                        List.of(
                                                WorkflowSteps.action(
                                                        "body",
                                                        vars ->
                                                                new FakePreparedAction<>(
                                                                        ActionResults.success(
                                                                                "ok"))))))
                        .build();
        WorkflowExecutionPlan plan = WorkflowPlanner.plan(workflow);
        WorkflowExecution execution = engine.executeWithTree(workflow, WorkflowInputs.empty());
        assertThat(execution.result().completed()).isTrue();

        WorkflowRecordingV2 recording =
                recorder.record(
                        new RecordingId("rec-loop"),
                        Instant.parse("2026-01-01T00:00:00Z"),
                        plan,
                        execution);

        assertThat(recording.nodes()).hasSize(1);
        RecordedExecutionNodeV2 wrapper = recording.nodes().get(0);
        assertThat(wrapper.step().stepType()).isEqualTo(WorkflowStepType.LOOP);
        assertThat(wrapper.branchSelection()).isEmpty();
        // 3 true iterations (with body) + 1 false stop = 4 LOOP_ITERATION children.
        assertThat(wrapper.children()).hasSize(4);
        for (int i = 0; i < 3; i++) {
            RecordedExecutionNodeV2 iteration = wrapper.children().get(i);
            assertThat(iteration.step().stepType()).isEqualTo(WorkflowStepType.LOOP_ITERATION);
            assertThat(iteration.branchSelection()).contains(WorkflowBranchSelection.THEN);
            assertThat(iteration.children()).hasSize(1);
        }
        RecordedExecutionNodeV2 stop = wrapper.children().get(3);
        assertThat(stop.branchSelection()).contains(WorkflowBranchSelection.NONE);
        assertThat(stop.children()).isEmpty();

        WorkflowRecordingV2 decoded = codec.decode(codec.encode(recording));
        assertThat(decoded).isEqualTo(recording);
    }

    @Test
    void capturesAZeroIterationLoopAsASingleFalseDecision() {
        Workflow workflow =
                Workflow.builder("wf-loop-zero")
                        .step(
                                WorkflowSteps.loop(
                                        "loop",
                                        new CountingUntilFalseCondition(0, new AtomicInteger()),
                                        5,
                                        List.of(
                                                WorkflowSteps.action(
                                                        "body",
                                                        vars ->
                                                                new FakePreparedAction<>(
                                                                        ActionResults.success(
                                                                                "ok"))))))
                        .build();
        WorkflowExecutionPlan plan = WorkflowPlanner.plan(workflow);
        WorkflowExecution execution = engine.executeWithTree(workflow, WorkflowInputs.empty());

        WorkflowRecordingV2 recording =
                recorder.record(
                        new RecordingId("rec-loop-zero"),
                        Instant.parse("2026-01-01T00:00:00Z"),
                        plan,
                        execution);

        RecordedExecutionNodeV2 wrapper = recording.nodes().get(0);
        assertThat(wrapper.children()).hasSize(1);
        assertThat(wrapper.children().get(0).branchSelection())
                .contains(WorkflowBranchSelection.NONE);

        assertThat(codec.decode(codec.encode(recording))).isEqualTo(recording);
    }

    @Test
    void capturesALimitExceededFailureWithNoSelectionOnTheFinalIteration() {
        AtomicInteger evaluations = new AtomicInteger();
        IWorkflowCondition alwaysTrue =
                new IWorkflowCondition() {
                    @Override
                    public boolean evaluate(IWorkflowVariables variables) {
                        evaluations.incrementAndGet();
                        return true;
                    }

                    @Override
                    public String describe() {
                        return "alwaysTrue";
                    }

                    @Override
                    public Set<WorkflowVariable<?>> referencedVariables() {
                        return Set.of();
                    }
                };
        Workflow workflow =
                Workflow.builder("wf-loop-limit")
                        .step(
                                WorkflowSteps.loop(
                                        "loop",
                                        alwaysTrue,
                                        2,
                                        List.of(
                                                WorkflowSteps.action(
                                                        "body",
                                                        vars ->
                                                                new FakePreparedAction<>(
                                                                        ActionResults.success(
                                                                                "ok"))))))
                        .build();
        WorkflowExecutionPlan plan = WorkflowPlanner.plan(workflow);
        WorkflowExecution execution = engine.executeWithTree(workflow, WorkflowInputs.empty());
        assertThat(execution.result().completed()).isFalse();

        WorkflowRecordingV2 recording =
                recorder.record(
                        new RecordingId("rec-loop-limit"),
                        Instant.parse("2026-01-01T00:00:00Z"),
                        plan,
                        execution);

        assertThat(recording.status()).isEqualTo(WorkflowStatus.FAILED);
        RecordedExecutionNodeV2 wrapper = recording.nodes().get(0);
        // 2 authorized iterations + the final, rejected (maxIterations+1)-th check.
        assertThat(wrapper.children()).hasSize(3);
        RecordedExecutionNodeV2 finalCheck = wrapper.children().get(2);
        assertThat(finalCheck.branchSelection()).isEmpty();
        assertThat(finalCheck.children()).isEmpty();
        assertThat(finalCheck.step().status()).isEqualTo(WorkflowStepStatus.FAILED);
        assertThat(finalCheck.step().failure().orElseThrow().type())
                .isEqualTo(WorkflowFailureType.LOOP_ITERATION_LIMIT_EXCEEDED);

        assertThat(codec.decode(codec.encode(recording))).isEqualTo(recording);
    }

    // ---- Hostile input: RecordingV2PlanTreeValidator must reject fabricated loop shapes -----

    @Test
    void aLoopNodeCarryingABranchSelectionIsRejected() {
        // RecordedExecutionNodeV2's own compact constructor rejects this before a recording could
        // even be attempted - that is itself the invariant under test.
        assertThatThrownBy(
                        () ->
                                new RecordedExecutionNodeV2(
                                        RecordingV2Fixtures.loopStep("loop"),
                                        Optional.of(WorkflowBranchSelection.THEN),
                                        List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aLoopIterationSelectingElseIsRejected() {
        WorkflowExecutionPlan plan = RecordingV2Fixtures.loopPlan("wf", "loop", "body");
        RecordedExecutionNodeV2 iteration =
                new RecordedExecutionNodeV2(
                        RecordingV2Fixtures.loopIterationStep("loop", true),
                        Optional.of(WorkflowBranchSelection.ELSE),
                        List.of());
        RecordedExecutionNodeV2 wrapper =
                new RecordedExecutionNodeV2(
                        RecordingV2Fixtures.loopStep("loop"), Optional.empty(), List.of(iteration));

        assertThatThrownBy(
                        () ->
                                RecordingV2Fixtures.recordingWith(
                                        "wf",
                                        plan,
                                        WorkflowStatus.COMPLETED,
                                        List.of(wrapper),
                                        Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aTrueOutcomeLoopIterationWithNoSelectionThatIsNotTheBoundFailureIsRejected() {
        WorkflowExecutionPlan plan = RecordingV2Fixtures.loopPlan("wf", "loop", "body");
        // True outcome, no selection, but SUCCEEDED (not the bound-exceeded FAILED state) - never
        // a state WorkflowEngine can produce.
        RecordedExecutionNodeV2 iteration =
                new RecordedExecutionNodeV2(
                        RecordingV2Fixtures.loopIterationStep("loop", true),
                        Optional.empty(),
                        List.of());
        RecordedExecutionNodeV2 wrapper =
                new RecordedExecutionNodeV2(
                        RecordingV2Fixtures.loopStep("loop"), Optional.empty(), List.of(iteration));

        assertThatThrownBy(
                        () ->
                                RecordingV2Fixtures.recordingWith(
                                        "wf",
                                        plan,
                                        WorkflowStatus.COMPLETED,
                                        List.of(wrapper),
                                        Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aLoopWithANonLoopIterationChildIsRejected() {
        WorkflowExecutionPlan plan = RecordingV2Fixtures.loopPlan("wf", "loop", "body");
        RecordedExecutionNodeV2 fakeChild =
                new RecordedExecutionNodeV2(
                        RecordingV2Fixtures.succeededActionStep("body", Optional.empty()),
                        Optional.empty(),
                        List.of());
        RecordedExecutionNodeV2 wrapper =
                new RecordedExecutionNodeV2(
                        RecordingV2Fixtures.loopStep("loop"), Optional.empty(), List.of(fakeChild));

        assertThatThrownBy(
                        () ->
                                RecordingV2Fixtures.recordingWith(
                                        "wf",
                                        plan,
                                        WorkflowStatus.COMPLETED,
                                        List.of(wrapper),
                                        Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---- Codec: encode(valid) -> decode never rejects; hostile decode never accepted ---------

    @Test
    void encodeThenDecodeOfANestedLoopRoundTripsExactly() {
        Workflow workflow =
                Workflow.builder("wf-nested-loop")
                        .step(
                                WorkflowSteps.loop(
                                        "outer",
                                        new CountingUntilFalseCondition(2, new AtomicInteger()),
                                        5,
                                        List.of(
                                                WorkflowSteps.loop(
                                                        "inner",
                                                        new CountingUntilFalseCondition(
                                                                2, new AtomicInteger()),
                                                        5,
                                                        List.of(
                                                                WorkflowSteps.action(
                                                                        "inner-body",
                                                                        vars ->
                                                                                new FakePreparedAction<>(
                                                                                        ActionResults
                                                                                                .success(
                                                                                                        "ok"))))))))
                        .build();
        WorkflowExecutionPlan plan = WorkflowPlanner.plan(workflow);
        WorkflowExecution execution = engine.executeWithTree(workflow, WorkflowInputs.empty());
        assertThat(execution.result().completed()).isTrue();

        WorkflowRecordingV2 recording =
                recorder.record(
                        new RecordingId("rec-nested-loop"),
                        Instant.parse("2026-01-01T00:00:00Z"),
                        plan,
                        execution);

        String encoded = codec.encode(recording);
        WorkflowRecordingV2 decoded = codec.decode(encoded);
        assertThat(decoded).isEqualTo(recording);
        assertThat(codec.encode(decoded)).isEqualTo(encoded);
    }

    @Test
    void decodeRejectsAMalformedLoopIterationMissingRequiredFields() {
        String hostileJson =
                "{"
                        + "\"schemaVersion\":2,"
                        + "\"recordingId\":\"r\","
                        + "\"capturedAt\":\"2026-01-01T00:00:00Z\","
                        + "\"workflowId\":\"wf\","
                        + "\"status\":\"COMPLETED\","
                        + "\"plan\":{\"workflowId\":\"wf\",\"nodes\":["
                        + "{\"stepId\":\"loop\",\"stepType\":\"LOOP\",\"guarded\":false,"
                        + "\"declaredOutput\":null,\"branches\":["
                        + "{\"kind\":\"THEN\",\"nodes\":["
                        + "{\"stepId\":\"body\",\"stepType\":\"ACTION\",\"guarded\":false,"
                        + "\"declaredOutput\":null,\"branches\":[]}"
                        + "]}]}"
                        + "]},"
                        + "\"nodes\":["
                        + "{\"step\":{\"stepId\":\"loop\",\"stepType\":\"LOOP\",\"status\":\"SUCCEEDED\","
                        + "\"condition\":null,\"output\":null,\"failure\":null,\"action\":null},"
                        + "\"branchSelection\":null,\"children\":["
                        + "{\"step\":{\"stepId\":\"loop#0\",\"stepType\":\"LOOP_ITERATION\","
                        + "\"status\":\"SUCCEEDED\",\"condition\":{\"outcome\":false,"
                        + "\"description\":\"d\"},\"output\":null,\"failure\":null,\"action\":null},"
                        + "\"branchSelection\":\"ELSE\",\"children\":[]}"
                        + "]}"
                        + "]"
                        + "}";

        assertThatThrownBy(() -> codec.decode(hostileJson))
                .isInstanceOf(RecordingFormatException.class);
    }
}
