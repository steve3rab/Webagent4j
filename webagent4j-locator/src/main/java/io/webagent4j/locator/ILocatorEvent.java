package io.webagent4j.locator;

import io.webagent4j.locator.api.LocatorDefinition;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/** Structured locator event emitted through an injected listener, never a global event bus. */
public sealed interface ILocatorEvent
        permits ILocatorEvent.ResolutionStarted,
                ILocatorEvent.StrategyExecuted,
                ILocatorEvent.CandidateFound,
                ILocatorEvent.ResolutionCompleted,
                ILocatorEvent.ResolutionFailed {

    /** Event timestamp. */
    Instant timestamp();

    /** Resolution started. */
    record ResolutionStarted(
            Instant timestamp, LocatorDefinition definition, LocatorResolutionPolicy policy)
            implements ILocatorEvent {

        /** Validates event data. */
        public ResolutionStarted {
            Objects.requireNonNull(timestamp, "timestamp");
            Objects.requireNonNull(definition, "definition");
            Objects.requireNonNull(policy, "policy");
        }
    }

    /** One strategy finished execution. */
    record StrategyExecuted(
            Instant timestamp, LocatorStrategyType strategy, int candidateCount, Duration duration)
            implements ILocatorEvent {

        /** Validates event data. */
        public StrategyExecuted {
            Objects.requireNonNull(timestamp, "timestamp");
            Objects.requireNonNull(strategy, "strategy");
            Objects.requireNonNull(duration, "duration");
            if (duration.isNegative()) {
                throw new IllegalArgumentException("duration cannot be negative");
            }
            if (candidateCount < 0) {
                throw new IllegalArgumentException("candidateCount cannot be negative");
            }
        }
    }

    /** One candidate was accepted or merged. */
    record CandidateFound(
            Instant timestamp, String identity, LocatorStrategyType strategy, double score)
            implements ILocatorEvent {

        /** Validates event data. */
        public CandidateFound {
            Objects.requireNonNull(timestamp, "timestamp");
            identity = Objects.requireNonNull(identity, "identity");
            Objects.requireNonNull(strategy, "strategy");
            if (score < 0.0 || score > 1.0) {
                throw new IllegalArgumentException("score must be between zero and one");
            }
        }
    }

    /** Resolution completed successfully. */
    record ResolutionCompleted(
            Instant timestamp, String selectedIdentity, double confidence, Duration duration)
            implements ILocatorEvent {

        /** Validates event data. */
        public ResolutionCompleted {
            Objects.requireNonNull(timestamp, "timestamp");
            selectedIdentity = Objects.requireNonNull(selectedIdentity, "selectedIdentity");
            Objects.requireNonNull(duration, "duration");
            if (duration.isNegative()) {
                throw new IllegalArgumentException("duration cannot be negative");
            }
            if (confidence < 0.0 || confidence > 1.0) {
                throw new IllegalArgumentException("confidence must be between zero and one");
            }
        }
    }

    /** Resolution failed or remained ambiguous. */
    record ResolutionFailed(
            Instant timestamp, String reason, Optional<LocatorDiagnostics> diagnostics)
            implements ILocatorEvent {

        /** Validates event data. */
        public ResolutionFailed {
            Objects.requireNonNull(timestamp, "timestamp");
            reason = Objects.requireNonNull(reason, "reason");
            diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
        }
    }
}
