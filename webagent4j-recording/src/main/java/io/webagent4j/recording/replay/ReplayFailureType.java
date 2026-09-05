package io.webagent4j.recording.replay;

import io.webagent4j.recording.WorkflowRecordingV2;
import io.webagent4j.workflow.Workflow;

/**
 * A structured, secret-free reason Deterministic Replay could not proceed.
 *
 * <p>This enum currently covers only the failure categories the structural/decision replay scope
 * implemented so far can actually produce - see {@code docs/recording.md} for the full taxonomy the
 * broader Deterministic Replay design anticipates (including categories specific to real
 * governed-target side-effect replay, such as a target revalidation failure, an action execution
 * failure, a missing replay input, or a secret mismatch) and why that scope is not yet implemented.
 * Constants are added here only once code exists that can produce them - this module does not
 * define a failure category for a feature that does not yet exist.
 *
 * <p>There is deliberately no constant here for an internally inconsistent recording (a tree that
 * does not correspond to its own plan) - not because that check does not exist, but because it can
 * never run at replay time in the first place: {@link WorkflowRecordingV2}'s own compact
 * constructor (see {@link io.webagent4j.recording.RecordingV2PlanTreeValidator}) already rejects
 * such a recording before an instance can exist, so {@link ReplayValidator} and {@link
 * WorkflowReplayer} can never receive one to classify with a replay-time failure type.
 */
public enum ReplayFailureType {

    /**
     * The recording's own {@link io.webagent4j.workflow.WorkflowExecutionPlan} is not identical to
     * the live workflow's current plan ({@code WorkflowPlanner.plan(workflow)}): the workflow
     * definition has changed, or {@code workflow} is simply a different workflow, since this
     * recording was captured. A recording is never replayed against a workflow it does not
     * structurally match.
     */
    INCOMPATIBLE_WORKFLOW,

    /**
     * The recording's overall {@link io.webagent4j.workflow.WorkflowStatus} is not one this replay
     * scope supports. Only a {@code COMPLETED} recording is replayable in this scope: replaying a
     * {@code FAILED} trace raises its own semantics questions (chiefly, what "replaying" a failure
     * that was often itself action-related would even mean without real side effects) that this
     * scope deliberately leaves undecided rather than guess at - see {@link WorkflowRecordingV2}
     * and {@link Workflow}.
     */
    UNSUPPORTED_STATUS,

    /**
     * A recorded {@link io.webagent4j.workflow.WorkflowStepType#LOOP}'s number of actually-run
     * iterations exceeds the live workflow's own declared {@code maxIterations} for that step -
     * added in 1.3.0. A recording's own structural plan never encodes {@code maxIterations} (see
     * {@code docs/workflow.md#bounded-loops}), so this check is made here, against the live {@link
     * Workflow}, rather than as part of {@link WorkflowRecordingV2}'s own construction-time
     * invariants: a hostile recording claiming to be a genuine, {@code COMPLETED} trace of a
     * bounded loop cannot smuggle in more iterations than that loop's live definition actually
     * authorizes, even though the recording's plan and tree are otherwise perfectly
     * self-consistent.
     */
    LOOP_ITERATION_COUNT_EXCEEDS_BOUND
}
