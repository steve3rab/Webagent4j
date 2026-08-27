package io.webagent4j.action.internal;

import io.webagent4j.action.ActionOptions;
import io.webagent4j.action.IStabilizationStrategy;
import io.webagent4j.action.StabilizationResult;
import io.webagent4j.action.policy.IActionPolicy;
import io.webagent4j.verification.IVerification;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Immutable internal action pipeline configuration. */
record ActionExecutionConfig(
        ActionOptions options,
        List<IVerification> preconditions,
        List<IVerification> postconditions,
        IStabilizationStrategy stabilization,
        boolean sensitive,
        boolean dryRun,
        Optional<IActionPolicy> actionPolicy) {

    ActionExecutionConfig {
        actionPolicy = Objects.requireNonNull(actionPolicy, "actionPolicy");
    }

    static ActionExecutionConfig defaults() {
        return new ActionExecutionConfig(
                ActionOptions.defaults(),
                List.of(),
                List.of(),
                (context, remaining) -> StabilizationResult.none(),
                false,
                false,
                Optional.empty());
    }
}
