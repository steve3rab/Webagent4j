package io.webagent4j.recording.replay;

import io.webagent4j.recording.RecordedExecutionNodeV2;
import io.webagent4j.recording.WorkflowRecordingV2;
import io.webagent4j.workflow.Workflow;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Deterministically replays a {@link WorkflowRecordingV2}'s recorded decision trace against a live
 * {@link Workflow}: strictly validates the pair (see {@link ReplayValidator}), then reconstructs
 * exactly what was recorded as a flattened, execution-ordered {@link ReplayedWorkflow} - never a
 * fresh decision.
 *
 * <p><b>This is structural/decision replay only, not side-effect replay:</b> {@link #replay} never
 * evaluates a condition, never invokes an {@code IWorkflowActionFactory}, never resolves or
 * verifies a backend target, and never performs any side effect. It is pure and stateless: the same
 * two arguments always produce the same {@link IReplayOutcome}. A recorded {@code CONDITIONAL}
 * step's decision is the one replayed - the non-selected branch contributes zero entries to the
 * result, exactly as it contributed zero nodes to the source recording. Real governed-target
 * side-effect replay (re-invoking an action against a freshly re-verified target) is a distinct,
 * not-yet-implemented capability - see {@code docs/recording.md} for the full scope decision and
 * what such a capability would additionally require (fresh secret input, exact-target revalidation,
 * and this codebase's existing exactly-once governed-execution guarantees, entirely unchanged).
 *
 * <p><b>No interruption/deadline guard:</b> unlike real action execution, this method performs no
 * backend call and produces no side effect to protect, so it does not check {@code
 * Thread.currentThread().isInterrupted()}. Its only cost is a bounded, in-memory walk of an
 * already-decoded recording (bounded by {@code JsonWorkflowRecordingV2Codec}'s own resource limits
 * when the recording came from JSON, or by {@code Workflow.MAX_CONDITIONAL_NESTING_DEPTH} when it
 * did not). The interruption/deadline discipline {@code ActionExecutor} and {@code WorkflowEngine}
 * both apply around every real side effect remains exactly as strict as ever for any future
 * side-effect-replay capability - this class has nothing to guard because it never calls anything.
 */
public final class WorkflowReplayer {

    private WorkflowReplayer() {}

    /**
     * Validates {@code recording} against {@code workflow} and, if compatible, replays its recorded
     * decision trace.
     */
    public static IReplayOutcome replay(WorkflowRecordingV2 recording, Workflow workflow) {
        Objects.requireNonNull(recording, "recording");
        Objects.requireNonNull(workflow, "workflow");
        Optional<ReplayValidationFailure> failure = ReplayValidator.validate(recording, workflow);
        if (failure.isPresent()) {
            return new IReplayOutcome.Rejected(failure.get());
        }
        List<ReplayedStep> steps = new ArrayList<>();
        flattenInto(recording.nodes(), steps);
        return new IReplayOutcome.Replayed(
                new ReplayedWorkflow(recording.workflowId(), List.copyOf(steps)));
    }

    private static void flattenInto(List<RecordedExecutionNodeV2> nodes, List<ReplayedStep> out) {
        for (RecordedExecutionNodeV2 node : nodes) {
            out.add(new ReplayedStep(node.step(), node.branchSelection()));
            flattenInto(node.children(), out);
        }
    }
}
