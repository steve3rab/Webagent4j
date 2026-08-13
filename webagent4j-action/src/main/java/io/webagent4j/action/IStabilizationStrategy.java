package io.webagent4j.action;

import java.time.Duration;

/** Lightweight backend-independent stabilization extension point. */
public interface IStabilizationStrategy {

    /** Awaits reliable post-action stability within the remaining budget. */
    StabilizationResult await(IActionContext context, Duration remaining);
}
