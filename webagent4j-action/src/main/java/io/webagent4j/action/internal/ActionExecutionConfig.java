package io.webagent4j.action.internal;

import io.webagent4j.action.ActionOptions;
import io.webagent4j.action.IStabilizationStrategy;
import io.webagent4j.action.StabilizationResult;
import io.webagent4j.verification.IVerification;
import java.util.List;

/** Immutable internal action pipeline configuration. */
record ActionExecutionConfig(
        ActionOptions options,
        List<IVerification> preconditions,
        List<IVerification> postconditions,
        IStabilizationStrategy stabilization,
        boolean sensitive) {

    static ActionExecutionConfig defaults() {
        return new ActionExecutionConfig(
                ActionOptions.defaults(),
                List.of(),
                List.of(),
                (context, remaining) -> StabilizationResult.none(),
                false);
    }
}
