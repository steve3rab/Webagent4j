package io.webagent4j.recording;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.webagent4j.workflow.IWorkflowCondition;
import io.webagent4j.workflow.IWorkflowStep;
import io.webagent4j.workflow.IWorkflowVariables;
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
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * REC2-PAR coverage: capturing a real {@code PARALLEL} {@code WorkflowExecution} into {@link
 * WorkflowRecordingV2}, round-tripping it through {@link JsonWorkflowRecordingV2Codec}, and
 * rejecting hostile/malformed parallel shapes at construction time. See {@code
 * docs/recording.md#parallel}.
 *
 * <p>Since a Workflow {@code ACTION} step is never permitted inside a {@code PARALLEL} branch (see
 * {@code io.webagent4j.workflow.WorkflowParallelActionSafetyTest}), the real-execution fixtures
 * below ({@code publish}/{@code failing}) use an always-true or always-throwing {@link
 * IWorkflowCondition} driving an {@code ifThen} whose body is a deterministic {@code ASSIGN} - the
 * allowed-step-type equivalent of the former test-only parallel-safe ACTION fixture. The
 * hostile/malformed-fixture tests below still use {@link RecordingV2Fixtures}' own {@code
 * succeededActionStep} as pure, synthetic recorded data - unrelated to what a real {@code
 * Workflow.Builder} can construct, since those tests validate {@code
 * RecordingV2PlanTreeValidator}'s shape checks directly against a hand-built {@link
 * RecordedExecutionNodeV2}/plan pair, never through real engine execution.
 */
class WorkflowParallelRecordingV2Test {

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

    private static IWorkflowStep failing(String id) {
        IWorkflowCondition throwing =
                new IWorkflowCondition() {
                    @Override
                    public boolean evaluate(IWorkflowVariables variables) {
                        throw new RuntimeException("boom");
                    }

                    @Override
                    public String describe() {
                        return "throwing";
                    }

                    @Override
                    public Set<WorkflowVariable<?>> referencedVariables() {
                        return Set.of();
                    }
                };
        return WorkflowSteps.ifThen(
                id,
                throwing,
                List.of(
                        WorkflowSteps.assign(
                                id + "-assign",
                                WorkflowVariable.publicValue(id + "NoopVar", String.class),
                                "x")));
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
                                                List.of(publish("a", outA)),
                                                List.of(publish("b", outB)))))
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
        IWorkflowStep failingB = failing("b");
        Workflow workflow =
                Workflow.builder("wf-par-fail")
                        .step(
                                WorkflowSteps.parallel(
                                        "par",
                                        List.of(List.of(publish("a", outA)), List.of(failingB))))
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
        IWorkflowStep failingB = failing("b");
        Workflow workflow =
                Workflow.builder("wf-par-fail-3")
                        .step(
                                WorkflowSteps.parallel(
                                        "par",
                                        List.of(
                                                List.of(publish("a", outA)),
                                                List.of(failingB),
                                                List.of(publish("c", outC)))))
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

    // --- REC2-PAR-INT-001: a real caller-interruption mid-join captures and round-trips ----
    // --- faithfully - the PARALLEL wrapper is FAILED/PARALLEL_STEP_INTERRUPTED with every ---
    // --- branch NOT_RUN, and RecordingV2PlanTreeValidator accepts this FAILED-with-children -
    // --- shape (see P1-1). --------------------------------------------------------------------

    @Test
    void capturesAndRoundTripsARealCallerInterruptionMidJoin() throws InterruptedException {
        java.util.concurrent.CountDownLatch bothStarted =
                new java.util.concurrent.CountDownLatch(2);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        IWorkflowCondition blockA = blockingCondition("a", bothStarted, release);
        IWorkflowCondition blockB = blockingCondition("b", bothStarted, release);
        WorkflowVariable<String> outA = WorkflowVariable.publicValue("outA", String.class);
        WorkflowVariable<String> outB = WorkflowVariable.publicValue("outB", String.class);
        Workflow workflow =
                Workflow.builder("wf-par-interrupted")
                        .step(
                                WorkflowSteps.parallel(
                                        "par",
                                        List.of(
                                                List.of(
                                                        WorkflowSteps.ifThen(
                                                                "a",
                                                                blockA,
                                                                List.of(
                                                                        WorkflowSteps.assign(
                                                                                "a-assign",
                                                                                outA,
                                                                                "a-value")))),
                                                List.of(
                                                        WorkflowSteps.ifThen(
                                                                "b",
                                                                blockB,
                                                                List.of(
                                                                        WorkflowSteps.assign(
                                                                                "b-assign",
                                                                                outB,
                                                                                "b-value")))))))
                        .build();

        java.util.concurrent.atomic.AtomicReference<WorkflowExecution> executionBox =
                new java.util.concurrent.atomic.AtomicReference<>();
        Thread executing =
                new Thread(
                        () ->
                                executionBox.set(
                                        engine.executeWithTree(workflow, WorkflowInputs.empty())));
        executing.start();
        bothStarted.await();
        executing.interrupt();
        executing.join(java.time.Duration.ofSeconds(15).toMillis());
        assertThat(executing.isAlive()).isFalse();
        release.countDown();

        WorkflowExecution execution = executionBox.get();
        assertThat(execution).isNotNull();
        assertThat(execution.result().completed()).isFalse();

        WorkflowExecutionPlan plan = WorkflowPlanner.plan(workflow);
        WorkflowRecordingV2 recording =
                recorder.record(
                        new RecordingId("rec-par-interrupted"),
                        Instant.parse("2026-01-01T00:00:00Z"),
                        plan,
                        execution);

        RecordedExecutionNodeV2 wrapper = recording.nodes().get(0);
        assertThat(wrapper.step().stepType()).isEqualTo(WorkflowStepType.PARALLEL);
        assertThat(wrapper.step().status()).isEqualTo(WorkflowStepStatus.FAILED);
        assertThat(wrapper.children()).hasSize(2);
        assertThat(wrapper.children())
                .allSatisfy(
                        branch ->
                                assertThat(branch.step().status())
                                        .isEqualTo(WorkflowStepStatus.NOT_RUN));

        // The hostile-shape validator must have accepted this FAILED-with-children PARALLEL
        // node - a JSON round-trip proves the encode/decode path agrees too.
        WorkflowRecordingV2 decoded = codec.decode(codec.encode(recording));
        assertThat(decoded).isEqualTo(recording);
    }

    private static IWorkflowCondition blockingCondition(
            String id,
            java.util.concurrent.CountDownLatch started,
            java.util.concurrent.CountDownLatch release) {
        return new IWorkflowCondition() {
            @Override
            public boolean evaluate(IWorkflowVariables variables) {
                started.countDown();
                try {
                    release.await();
                    return true;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return true;
                }
            }

            @Override
            public String describe() {
                return id;
            }

            @Override
            public Set<WorkflowVariable<?>> referencedVariables() {
                return Set.of();
            }
        };
    }

    // --- PAR-INT-008: a fabricated SUCCEEDED branch under a FAILED/PARALLEL_STEP_INTERRUPTED -
    // --- wrapper is rejected - WorkflowResult's own invariant forbids anything but NOT_RUN ---
    // --- after a FAILED step, so the real engine can never produce this shape. --------------

    @Test
    void fabricatedSucceededBranchUnderInterruptedWrapperRejected() {
        WorkflowExecutionPlan plan = RecordingV2Fixtures.parallelPlan("wf", "par", "a", "b");
        RecordedExecutionNodeV2 hostileInterrupted =
                new RecordedExecutionNodeV2(
                        RecordingV2Fixtures.interruptedParallelStep("par"),
                        Optional.empty(),
                        List.of(
                                new RecordedExecutionNodeV2(
                                        RecordingV2Fixtures.parallelBranchStep("par@0"),
                                        Optional.empty(),
                                        List.of(
                                                RecordingV2Fixtures.leaf(
                                                        RecordingV2Fixtures.succeededActionStep(
                                                                "a@0", Optional.empty())))),
                                RecordingV2Fixtures.leaf(
                                        RecordingV2Fixtures.notRunParallelBranchStep("par@1"))));

        assertThatThrownBy(
                        () ->
                                RecordingV2Fixtures.recordingWith(
                                        "wf",
                                        plan,
                                        WorkflowStatus.FAILED,
                                        List.of(hostileInterrupted),
                                        Optional.of(
                                                RecordingV2Fixtures.parallelInterruptedFailure(
                                                        "par"))))
                .isInstanceOf(IllegalArgumentException.class);
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
