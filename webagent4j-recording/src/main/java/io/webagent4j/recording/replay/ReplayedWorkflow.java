package io.webagent4j.recording.replay;

import io.webagent4j.workflow.WorkflowId;
import java.util.List;
import java.util.Objects;

/**
 * The result of successfully replaying a {@code WorkflowRecordingV2}'s recorded decision trace
 * against a live workflow: the exact recorded step sequence and branch decisions, confirmed
 * structurally compatible with that workflow's current definition, and nothing else.
 *
 * <p>Only ever produced for a {@code COMPLETED} recording (see {@link ReplayValidator}) - a {@link
 * WorkflowReplayer} caller never receives one for a rejected or incompatible recording, see {@link
 * IReplayOutcome}. Producing this never evaluates a condition, never invokes an {@code
 * IWorkflowActionFactory}, and never performs any side effect: it is data, not a program, exactly
 * like the recording it was built from.
 *
 * @param workflowId the replayed workflow's identifier
 * @param steps every recorded step, flattened into execution order - see {@link ReplayedStep}
 */
public record ReplayedWorkflow(WorkflowId workflowId, List<ReplayedStep> steps) {

    /** Validates and defensively copies replay data. */
    public ReplayedWorkflow {
        Objects.requireNonNull(workflowId, "workflowId");
        steps = List.copyOf(Objects.requireNonNull(steps, "steps"));
    }
}
