package io.webagent4j.recording.replay;

/**
 * The result of {@link WorkflowReplayer#replay}: either a successfully replayed decision trace, or
 * a structured, secret-free reason it was rejected before any replay was attempted.
 *
 * <p>Sealed so a caller must handle both cases explicitly - there is no default or partial replay
 * outcome.
 */
public sealed interface IReplayOutcome {

    /** {@code recording} was compatible with the live workflow and has been replayed. */
    record Replayed(ReplayedWorkflow workflow) implements IReplayOutcome {}

    /** {@code recording} was rejected before any replay was attempted. */
    record Rejected(ReplayValidationFailure failure) implements IReplayOutcome {}
}
