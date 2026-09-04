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
    UNSUPPORTED_STATUS
}
