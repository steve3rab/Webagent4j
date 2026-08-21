package io.webagent4j.recording;

import java.util.Objects;

/**
 * One structured difference found by {@link WorkflowReplayVerifier} between a recorded execution
 * and a new actual execution.
 *
 * @param type the stable category of this difference
 * @param path a fixed, deterministic schema-style path identifying where the difference was found
 *     (for example {@code "$.workflow.steps[2].status"})
 * @param expected the recorded value, rendered as safe diagnostic text
 * @param actual the actual result's value, rendered as safe diagnostic text
 */
public record WorkflowReplayMismatch(
        WorkflowReplayMismatchType type, String path, String expected, String actual) {

    /** Validates mismatch data. */
    public WorkflowReplayMismatch {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(expected, "expected");
        Objects.requireNonNull(actual, "actual");
    }
}
