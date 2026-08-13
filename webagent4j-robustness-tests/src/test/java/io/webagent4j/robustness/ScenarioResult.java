package io.webagent4j.robustness;

import io.webagent4j.locator.LocatorResolutionStatus;
import java.time.Duration;
import java.util.Objects;

record ScenarioResult(
        RobustnessScenario scenario,
        LocatorResolutionStatus status,
        boolean exact,
        boolean fuzzy,
        boolean actionSucceeded,
        boolean verificationFailed,
        boolean wrongTarget,
        boolean dynamicReresolutionSucceeded,
        boolean unexpectedException,
        Duration resolutionDuration,
        String actualTarget,
        String diagnostics) {

    ScenarioResult {
        Objects.requireNonNull(scenario, "scenario");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(resolutionDuration, "resolutionDuration");
        actualTarget = actualTarget == null ? "" : actualTarget;
        diagnostics = diagnostics == null ? "" : diagnostics;
    }

    boolean expectedOutcome() {
        return switch (scenario.expectation()) {
            case MUST_RESOLVE_EXACT ->
                    status == LocatorResolutionStatus.RESOLVED && exact && !wrongTarget;
            case MUST_RESOLVE_FUZZY ->
                    status == LocatorResolutionStatus.RESOLVED && fuzzy && !wrongTarget;
            case MUST_BE_AMBIGUOUS -> status == LocatorResolutionStatus.AMBIGUOUS;
            case MUST_BE_UNRESOLVABLE -> status == LocatorResolutionStatus.UNRESOLVABLE;
            case MUST_FAIL_INTERACTABILITY -> status == LocatorResolutionStatus.NOT_INTERACTABLE;
            case MUST_TIMEOUT -> status == LocatorResolutionStatus.TIMEOUT;
            case MUST_EXECUTE_AND_VERIFY ->
                    status == LocatorResolutionStatus.RESOLVED
                            && actionSucceeded
                            && !verificationFailed
                            && !wrongTarget;
        };
    }

    FailureClassification failureClassification() {
        if (expectedOutcome() && !unexpectedException) {
            return FailureClassification.NONE;
        }
        if (wrongTarget) {
            return FailureClassification.WRONG_TARGET;
        }
        if (unexpectedException) {
            return FailureClassification.OBSERVATION_FAILURE;
        }
        if (verificationFailed) {
            return FailureClassification.VERIFICATION_FAILURE;
        }
        if (scenario.expectation() == ScenarioExpectation.MUST_EXECUTE_AND_VERIFY
                && !actionSucceeded) {
            return FailureClassification.ACTION_FAILURE;
        }
        return switch (status) {
            case AMBIGUOUS -> FailureClassification.AMBIGUITY_FAILURE;
            case NOT_INTERACTABLE -> FailureClassification.INTERACTABILITY_FAILURE;
            case TIMEOUT -> FailureClassification.TIMEOUT_FAILURE;
            case RESOLVED, UNRESOLVABLE -> FailureClassification.LOCATOR_FAILURE;
        };
    }
}
