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

    // ---- RPL-LOOP-BOUND: nested loops must be resolved structurally, never by runtime ID -----

    /** True, false, true, false, ... - exactly one authorized iteration per continuation cycle. */
    private static final class AlternatingCondition implements IWorkflowCondition {
        private final AtomicInteger evaluations = new AtomicInteger();

        @Override
        public boolean evaluate(IWorkflowVariables variables) {
            return evaluations.getAndIncrement() % 2 == 0;
        }

        @Override
        public String describe() {
            return "alternating";
        }

        @Override
        public Set<WorkflowVariable<?>> referencedVariables() {
            return Set.of();
        }
    }

    private static Workflow nestedLoopWorkflow(
            String workflowId,
            String outerId,
            int outerMax,
            IWorkflowCondition outerCondition,
            String innerId,
            int innerMax,
            IWorkflowCondition innerCondition,
            String bodyId) {
        return Workflow.builder(workflowId)
                .step(
                        WorkflowSteps.loop(
                                outerId,
                                outerCondition,
                                outerMax,
                                List.of(
                                        WorkflowSteps.loop(
                                                innerId,
                                                innerCondition,
                                                innerMax,
                                                List.of(
                                                        WorkflowSteps.action(
                                                                bodyId,
                                                                vars ->
                                                                        new SentinelPreparedAction(
                                                                                "ok")))))))
                .build();
    }

    @Test
    void bound003NestedLoopWithinBoundIsAccepted() {
        // Each of 2 outer iterations runs its own inner loop exactly once (AlternatingCondition's
        // shared, cumulative counter still produces exactly one authorized inner iteration per
        // outer attempt: true, false, true, false), well within innerMax=2.
        Workflow workflow =
                nestedLoopWorkflow(
                        "wf-nested-within-bound",
                        "outer",
                        3,
                        new CountingUntilFalseCondition(2, new AtomicInteger()),
                        "inner",
                        2,
                        new AlternatingCondition(),
                        "body");
        WorkflowExecutionPlan plan = WorkflowPlanner.plan(workflow);
        WorkflowExecution execution = engine.executeWithTree(workflow, WorkflowInputs.empty());
        assertThat(execution.result().completed()).isTrue();
        WorkflowRecordingV2 recording =
                recorder.record(new RecordingId("rec-nested"), Instant.now(), plan, execution);

        assertThat(ReplayValidator.validate(recording, workflow)).isEmpty();
        assertThat(WorkflowReplayer.replay(recording, workflow))
                .isInstanceOf(IReplayOutcome.Replayed.class);
    }

    private static RecordedWorkflowStepV2 loopWrapperStep(String stepId) {
        return new RecordedWorkflowStepV2(
                new WorkflowStepId(stepId),
                WorkflowStepType.LOOP,
                WorkflowStepStatus.SUCCEEDED,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
    }

    private static RecordedWorkflowStepV2 iterationDecisionStep(String stepId, boolean outcome) {
        return new RecordedWorkflowStepV2(
                new WorkflowStepId(stepId),
                WorkflowStepType.LOOP_ITERATION,
                WorkflowStepStatus.SUCCEEDED,
                Optional.of(new RecordedCondition(outcome, "d")),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
    }

    private static RecordedExecutionNodeV2 succeededBodyLeaf(String stepId) {
        RecordedWorkflowStepV2 bodyStep =
                new RecordedWorkflowStepV2(
                        new WorkflowStepId(stepId),
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
        return new RecordedExecutionNodeV2(bodyStep, Optional.empty(), List.of());
    }

    /**
     * Builds {@code authorizedCount} fabricated {@code THEN} iterations - each carrying one {@code
     * childNode} as its own single body child - plus one final {@code NONE} stop, all under {@code
     * loopId}'s own runtime iteration qualification.
     */
    private static List<RecordedExecutionNodeV2> fabricatedIterations(
            int authorizedCount,
            String loopId,
            java.util.function.IntFunction<RecordedExecutionNodeV2> childAt) {
        List<RecordedExecutionNodeV2> iterations = new ArrayList<>();
        for (int i = 0; i < authorizedCount; i++) {
            iterations.add(
                    new RecordedExecutionNodeV2(
                            iterationDecisionStep(loopId + "#" + i, true),
                            Optional.of(WorkflowBranchSelection.THEN),
                            List.of(childAt.apply(i))));
        }
        iterations.add(
                new RecordedExecutionNodeV2(
                        iterationDecisionStep(loopId + "#" + authorizedCount, false),
                        Optional.of(WorkflowBranchSelection.NONE),
                        List.of()));
        return iterations;
    }

    private static WorkflowRecordingV2 recordingOf(
            WorkflowExecutionPlan plan, RecordedExecutionNodeV2 wrapper) {
        return new WorkflowRecordingV2(
                RecordingSchemaVersionV2.V2,
                new RecordingId("hostile-nested"),
                Instant.now(),
                plan.workflowId(),
                WorkflowStatus.COMPLETED,
                plan,
                List.of(wrapper),
                Optional.empty());
    }

    @Test
    void bound004And006InnerLoopExceedingItsOwnBoundIsRejectedEvenWithOuterWithinBound() {
        // outerMax=5, innerMax=2 - a single, valid outer iteration whose inner loop fabricates 3
        // authorized ("THEN") iterations, one more than innerMax permits.
        Workflow workflow =
                nestedLoopWorkflow(
                        "wf-inner-exceeds",
                        "outer",
                        5,
                        new CountingUntilFalseCondition(100, new AtomicInteger()),
                        "inner",
                        2,
                        new CountingUntilFalseCondition(100, new AtomicInteger()),
                        "body");
        WorkflowExecutionPlan plan = WorkflowPlanner.plan(workflow);

        List<RecordedExecutionNodeV2> innerIterations =
                fabricatedIterations(3, "inner#0", i -> succeededBodyLeaf("body#0#" + i));
        RecordedExecutionNodeV2 innerWrapper =
                new RecordedExecutionNodeV2(
                        loopWrapperStep("inner#0"), Optional.empty(), innerIterations);
        List<RecordedExecutionNodeV2> outerIterations =
                fabricatedIterations(1, "outer", i -> innerWrapper);
        RecordedExecutionNodeV2 outerWrapper =
                new RecordedExecutionNodeV2(
                        loopWrapperStep("outer"), Optional.empty(), outerIterations);

        WorkflowRecordingV2 hostileRecording = recordingOf(plan, outerWrapper);

        Optional<ReplayValidationFailure> validation =
                ReplayValidator.validate(hostileRecording, workflow);
        assertThat(validation).isPresent();
        assertThat(validation.get().type())
                .isEqualTo(ReplayFailureType.LOOP_ITERATION_COUNT_EXCEEDS_BOUND);
        assertThat(WorkflowReplayer.replay(hostileRecording, workflow))
                .isInstanceOf(IReplayOutcome.Rejected.class);
    }

    @Test
    void bound007OuterLoopExceedingItsOwnBoundIsRejectedEvenWithInnerWithinBound() {
        // outerMax=2, innerMax=5 - 3 fabricated authorized outer iterations (one more than
        // outerMax permits), each with a genuinely-valid single inner iteration.
        Workflow workflow =
                nestedLoopWorkflow(
                        "wf-outer-exceeds",
                        "outer",
                        2,
                        new CountingUntilFalseCondition(100, new AtomicInteger()),
                        "inner",
                        5,
                        new CountingUntilFalseCondition(100, new AtomicInteger()),
                        "body");
        WorkflowExecutionPlan plan = WorkflowPlanner.plan(workflow);

        java.util.function.IntFunction<RecordedExecutionNodeV2> innerWrapperAt =
                outerIndex -> {
                    List<RecordedExecutionNodeV2> innerIterations =
                            fabricatedIterations(
                                    1,
                                    "inner#" + outerIndex,
                                    i -> succeededBodyLeaf("body#" + outerIndex + "#" + i));
                    return new RecordedExecutionNodeV2(
                            loopWrapperStep("inner#" + outerIndex),
                            Optional.empty(),
                            innerIterations);
                };
        List<RecordedExecutionNodeV2> outerIterations =
                fabricatedIterations(3, "outer", innerWrapperAt);
        RecordedExecutionNodeV2 outerWrapper =
                new RecordedExecutionNodeV2(
                        loopWrapperStep("outer"), Optional.empty(), outerIterations);

        WorkflowRecordingV2 hostileRecording = recordingOf(plan, outerWrapper);

        Optional<ReplayValidationFailure> validation =
                ReplayValidator.validate(hostileRecording, workflow);
        assertThat(validation).isPresent();
        assertThat(validation.get().type())
                .isEqualTo(ReplayFailureType.LOOP_ITERATION_COUNT_EXCEEDS_BOUND);
        assertThat(WorkflowReplayer.replay(hostileRecording, workflow))
                .isInstanceOf(IReplayOutcome.Rejected.class);
    }

    @Test
    void bound005TheSameNestedLoopDeclarationAcrossMultipleOuterIterationsIsValidatedEachTime() {
        // 3 outer iterations, each running its own inner loop exactly once - every runtime
        // occurrence of "inner" (inner#0, inner#1, inner#2) must be checked against the identical
        // live bound (innerMax=2), never confused with one another.
        Workflow workflow =
                nestedLoopWorkflow(
                        "wf-repeated-inner",
                        "outer",
                        3,
                        new CountingUntilFalseCondition(3, new AtomicInteger()),
                        "inner",
                        2,
                        new AlternatingCondition(),
                        "body");
        WorkflowExecutionPlan plan = WorkflowPlanner.plan(workflow);
        WorkflowExecution execution = engine.executeWithTree(workflow, WorkflowInputs.empty());
        assertThat(execution.result().completed()).isTrue();
        WorkflowRecordingV2 recording =
                recorder.record(
                        new RecordingId("rec-repeated-inner"), Instant.now(), plan, execution);

        assertThat(ReplayValidator.validate(recording, workflow)).isEmpty();
    }

    @Test
    void bound009ALiteralHashDigitSequenceInADeclaredIdIsResolvedCorrectlyNeverStripped() {
        // The declared loop ID is itself "inner#0" - if the fix ever stripped a trailing
        // "#<digits>"
        // heuristically, this declared ID would be corrupted into "inner" and fail to resolve.
        Workflow workflow =
                nestedLoopWorkflow(
                        "wf-literal-hash-id",
                        "outer",
                        2,
                        new CountingUntilFalseCondition(1, new AtomicInteger()),
                        "inner#0",
                        2,
                        new AlternatingCondition(),
                        "body");
        WorkflowExecutionPlan plan = WorkflowPlanner.plan(workflow);
        WorkflowExecution execution = engine.executeWithTree(workflow, WorkflowInputs.empty());
        assertThat(execution.result().completed()).isTrue();
        WorkflowRecordingV2 recording =
                recorder.record(
                        new RecordingId("rec-literal-hash"), Instant.now(), plan, execution);

        assertThat(ReplayValidator.validate(recording, workflow)).isEmpty();
    }

    private static IWorkflowStep boundedLoop(String stepId, List<IWorkflowStep> body) {
        return WorkflowSteps.loop(
                stepId, new CountingUntilFalseCondition(1, new AtomicInteger()), 2, body);
    }

    @Test
    void bound010ATriplyNestedLoopResolvesTheInnermostBoundStructurally() {
        IWorkflowStep innerLoop =
                boundedLoop(
                        "inner",
                        List.of(
                                WorkflowSteps.action(
                                        "body", vars -> new SentinelPreparedAction("ok"))));
        IWorkflowStep middleLoop = boundedLoop("middle", List.of(innerLoop));
        IWorkflowStep outerLoop = boundedLoop("outer", List.of(middleLoop));
        Workflow workflow = Workflow.builder("wf-triple-nested").step(outerLoop).build();
        WorkflowExecutionPlan plan = WorkflowPlanner.plan(workflow);
        WorkflowExecution execution = engine.executeWithTree(workflow, WorkflowInputs.empty());
        assertThat(execution.result().completed()).isTrue();
        WorkflowRecordingV2 recording =
                recorder.record(
                        new RecordingId("rec-triple-nested"), Instant.now(), plan, execution);

        // Runtime IDs at the deepest level compose like "inner#0#0#0" - resolved structurally
        // against the live "inner" declaration, never by parsing that composed string.
        assertThat(ReplayValidator.validate(recording, workflow)).isEmpty();
        assertThat(WorkflowReplayer.replay(recording, workflow))
                .isInstanceOf(IReplayOutcome.Replayed.class);
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
